package entropydata.snowflake;

import entropydata.sdk.EntropyDataAssetsSynchronizer;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.EntropyDataEventListener;
import entropydata.sdk.EntropyDataStateRepositoryRemote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import snowflake.client.ApiClient;

@SpringBootApplication(scanBasePackages = "entropydata")
@ConfigurationPropertiesScan("entropydata")
@EnableScheduling
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public EntropyDataClient entropyDataClient(
      @Value("${entropydata.client.host}") String host,
      @Value("${entropydata.client.apikey}") String apiKey) {
    return new EntropyDataClient(host, apiKey);
  }

  @Bean
  public ApiClient snowflakeApiClient(SnowflakeProperties snowflakeProperties) {
    ApiClient snowflakeApiClient = new ApiClient();
    snowflakeApiClient.addDefaultHeader("X-Snowflake-Authorization-Token-Type", "KEYPAIR_JWT");
    snowflakeApiClient.setBasePath("https://%s.snowflakecomputing.com".formatted(snowflakeProperties.account()));
    snowflakeApiClient.setBearerToken(new BearerTokenSupplier(snowflakeProperties));
    return snowflakeApiClient;
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "entropydata.client.snowflake.accessmanagement.enabled", havingValue = "true")
  public EntropyDataEventListener entropyDataEventListener(
      EntropyDataClient client,
      SnowflakeProperties snowflakeProperties,
      ApiClient snowflakeApiClient,
      TaskExecutor taskExecutor) {
    var connectorId = snowflakeProperties.accessmanagement().connectorid();
    var eventHandler = new SnowflakeAccessManagementHandler(client, snowflakeApiClient);
    var stateRepository = new EntropyDataStateRepositoryRemote(connectorId, client);
    var entropyDataEventListener = new EntropyDataEventListener(connectorId, "accessmanagement", client, eventHandler, stateRepository);
    taskExecutor.execute(entropyDataEventListener::start);
    return entropyDataEventListener;
  }

  @Bean
  @ConditionalOnProperty(value = "entropydata.client.snowflake.assets.enabled", havingValue = "true")
  public AssetsSynchronizationHealth assetsSynchronizationHealth(SnowflakeProperties snowflakeProperties) {
    return new AssetsSynchronizationHealth(snowflakeProperties.assets().pollinterval());
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "entropydata.client.snowflake.assets.enabled", havingValue = "true")
  public EntropyDataAssetsSynchronizer entropyDataAssetsSynchronizer(
      SnowflakeProperties snowflakeProperties,
      EntropyDataClient client,
      ApiClient snowflakeApiClient,
      AssetsSynchronizationHealth assetsSynchronizationHealth,
      TaskExecutor taskExecutor) {
    var connectorId = snowflakeProperties.assets().connectorid();
    var assetsProvider = new SnowflakeAssetsProvider(snowflakeProperties, snowflakeApiClient);
    var entropyDataAssetsSynchronizer = new EntropyDataAssetsSynchronizer(connectorId, client,
        assetsSynchronizationHealth.wrap(assetsProvider));
    if (snowflakeProperties.assets().pollinterval() != null) {
      entropyDataAssetsSynchronizer.setDelay(snowflakeProperties.assets().pollinterval());
    }

    taskExecutor.execute(entropyDataAssetsSynchronizer::start);
    return entropyDataAssetsSynchronizer;
  }

  @Bean
  public SimpleAsyncTaskExecutor taskExecutor() {
    return new SimpleAsyncTaskExecutor();
  }

}
