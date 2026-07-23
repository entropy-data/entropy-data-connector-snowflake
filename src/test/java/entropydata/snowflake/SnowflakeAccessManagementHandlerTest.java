package entropydata.snowflake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.client.api.AccessApi;
import entropydata.sdk.client.api.DataProductsApi;
import entropydata.sdk.client.model.Access;
import entropydata.sdk.client.model.AccessActivatedEvent;
import entropydata.sdk.client.model.AccessDeactivatedEvent;
import entropydata.sdk.client.model.AccessDeprovisioningSucceededReport;
import entropydata.sdk.client.model.AccessProvider;
import entropydata.sdk.client.model.AccessProvisioningFailedReport;
import entropydata.sdk.client.model.AccessProvisioningStartedReport;
import entropydata.sdk.client.model.AccessProvisioningSucceededReport;
import entropydata.sdk.client.model.DataUsageAgreementConsumer;
import entropydata.sdk.client.model.DataUsageAgreementInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import snowflake.client.api.RoleApi;
import snowflake.client.api.SchemaApi;
import snowflake.client.api.UserApi;
import snowflake.client.model.schema.SchemaInfo;
import snowflake.client.model.user.User;

class SnowflakeAccessManagementHandlerTest {

  private EntropyDataClient client;
  private AccessApi accessApi;
  private DataProductsApi dataProductsApi;
  private ObjectMapper objectMapper;

  private SnowflakeAccessManagementHandler handler;

  @BeforeEach
  void setUp() {
    client = mock(EntropyDataClient.class);
    accessApi = mock(AccessApi.class);
    dataProductsApi = mock(DataProductsApi.class);
    var sdkApiClient = mock(entropydata.sdk.client.ApiClient.class);
    objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    when(client.getApiClient()).thenReturn(sdkApiClient);
    when(sdkApiClient.getObjectMapper()).thenReturn(objectMapper);
    when(client.getAccessApi()).thenReturn(accessApi);
    when(client.getDataProductsApi()).thenReturn(dataProductsApi);

    handler = new SnowflakeAccessManagementHandler(client, mock(snowflake.client.ApiClient.class));
  }

  @Test
  void reportsProvisioningStartedThenSucceededOnGrant() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(providerDataProduct());

    var schemaInfo = mock(SchemaInfo.class);
    when(schemaInfo.getName()).thenReturn("MY_SCHEMA");
    when(schemaInfo.getDatabaseName()).thenReturn("MY_DB");

    try (var schemaApis = mockConstruction(SchemaApi.class, (m, ctx) ->
            when(m.listSchemas(any(), any(), any(), any(), any(), any())).thenReturn(List.of(schemaInfo)));
        var roleApis = mockConstruction(RoleApi.class, (m, ctx) ->
            when(m.listRoles(any(), any(), any(), any())).thenReturn(List.of()));
        var userApis = mockConstruction(UserApi.class, (m, ctx) ->
            when(m.listUsers(any(), any(), any(), any()))
                .thenReturn(List.of(new User().name("SNOW_ALICE").email("alice@example.com"))))) {

      var event = new AccessActivatedEvent();
      event.setId("a-1");
      handler.onAccessActivatedEvent(event);
    }

    // started is reported before the grant, succeeded after it, with the granted schema as reference
    var order = inOrder(accessApi);
    order.verify(accessApi).reportProvisioningStarted(eq("a-1"), any(AccessProvisioningStartedReport.class));
    var succeeded = ArgumentCaptor.forClass(AccessProvisioningSucceededReport.class);
    order.verify(accessApi).reportProvisioningSucceeded(eq("a-1"), succeeded.capture());
    assertThat(succeeded.getValue().getPlatform()).isEqualTo("snowflake");
    assertThat(succeeded.getValue().getReference()).isEqualTo("MY_DB.MY_SCHEMA");
    verify(accessApi, never()).reportProvisioningFailed(anyString(), any());
  }

  @Test
  void reportsProvisioningFailedWhenGrantThrows() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(providerDataProduct());

    try (var schemaApis = mockConstruction(SchemaApi.class, (m, ctx) ->
        // no schema matches -> getSnowflakeSchema throws, which the handler must report and swallow
        when(m.listSchemas(any(), any(), any(), any(), any(), any())).thenReturn(List.of()))) {

      var event = new AccessActivatedEvent();
      event.setId("a-1");
      handler.onAccessActivatedEvent(event);
    }

    var order = inOrder(accessApi);
    order.verify(accessApi).reportProvisioningStarted(eq("a-1"), any(AccessProvisioningStartedReport.class));
    var failed = ArgumentCaptor.forClass(AccessProvisioningFailedReport.class);
    order.verify(accessApi).reportProvisioningFailed(eq("a-1"), failed.capture());
    assertThat(failed.getValue().getDiagnostics()).contains("MY_DB.MY_SCHEMA not found");
    verify(accessApi, never()).reportProvisioningSucceeded(anyString(), any());
  }

  @Test
  void reportsDeprovisioningStartedThenSucceededOnRevoke() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(providerDataProduct());

    try (var roleApis = mockConstruction(RoleApi.class)) {
      var event = new AccessDeactivatedEvent();
      event.setId("a-1");
      handler.onAccessDeactivatedEvent(event);

      // the access role is deleted, then deprovisioning-succeeded is reported with it as reference
      verify(roleApis.constructed().get(0)).deleteRole("access_a_1", true);
    }

    var order = inOrder(accessApi);
    order.verify(accessApi).reportDeprovisioningStarted(eq("a-1"), any(AccessProvisioningStartedReport.class));
    var succeeded = ArgumentCaptor.forClass(AccessDeprovisioningSucceededReport.class);
    order.verify(accessApi).reportDeprovisioningSucceeded(eq("a-1"), succeeded.capture());
    assertThat(succeeded.getValue().getReference()).isEqualTo("access_a_1");
  }

  @Test
  void doesNotReportProvisioningWhenNotApplicable() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    // the provider output port is not a Snowflake port, so another connector owns this access
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(Map.of(
        "outputPorts", List.of(Map.of("id", "op-snowflake", "type", "databricks"))));

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    verify(accessApi, never()).reportProvisioningStarted(anyString(), any());
    verify(accessApi, never()).reportProvisioningSucceeded(anyString(), any());
    verify(accessApi, never()).reportProvisioningFailed(anyString(), any());
  }

  private Access activeUserAccess() {
    var access = new Access();
    access.setId("a-1");
    access.setProvider(new AccessProvider().dataProductId("provider-dp").outputPortId("op-snowflake"));
    access.setConsumer(new DataUsageAgreementConsumer().userId("alice@example.com"));
    access.setInfo(objectMapper.convertValue(Map.of("purpose", "test", "active", true), DataUsageAgreementInfo.class));
    return access;
  }

  // A DPS-style provider data product whose Snowflake output port carries the server inline, so the
  // server resolves without a data-contract fetch.
  private static Map<String, Object> providerDataProduct() {
    return Map.of("outputPorts", List.of(Map.of(
        "id", "op-snowflake",
        "type", "snowflake",
        "server", Map.of("database", "MY_DB", "schema", "MY_SCHEMA"))));
  }
}
