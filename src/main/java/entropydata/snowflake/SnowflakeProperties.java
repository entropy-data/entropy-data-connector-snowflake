package entropydata.snowflake;

import java.io.File;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entropydata.client.snowflake")
public record SnowflakeProperties(
    String account,
    String user,
    File privatekeyfile,
    AssetsProperties assets,
    AccessmanagementProperties accessmanagement
) {

  public record AssetsProperties(
      Boolean enabled,
      String connectorid,
      Duration pollinterval
  ) {

  }

  public record AccessmanagementProperties(
      Boolean enabled,
      String connectorid
  ) {

  }


}
