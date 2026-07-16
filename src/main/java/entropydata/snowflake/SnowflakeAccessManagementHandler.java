package entropydata.snowflake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.EntropyDataEventHandler;
import entropydata.sdk.client.ApiException;
import entropydata.sdk.client.model.Access;
import entropydata.sdk.client.model.AccessActivatedEvent;
import entropydata.sdk.client.model.AccessDeactivatedEvent;
import entropydata.sdk.client.model.DataProduct;
import entropydata.sdk.client.model.Team;
import entropydata.sdk.client.model.TeamMembersInner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import snowflake.client.ApiClient;
import snowflake.client.api.RoleApi;
import snowflake.client.api.SchemaApi;
import snowflake.client.api.UserApi;
import snowflake.client.model.role.ContainingScope;
import snowflake.client.model.role.Grant;
import snowflake.client.model.role.Role;
import snowflake.client.model.role.Securable;
import snowflake.client.model.schema.SchemaInfo;
import snowflake.client.model.user.User;

public class SnowflakeAccessManagementHandler implements EntropyDataEventHandler {

  private static final Logger log = LoggerFactory.getLogger(SnowflakeAccessManagementHandler.class);

  private final EntropyDataClient client;
  private final ApiClient snowflakeApiClient;
  private final ObjectMapper objectMapper;

  public SnowflakeAccessManagementHandler(
      EntropyDataClient client, ApiClient snowflakeApiClient) {
    this.client = client;
    this.snowflakeApiClient = snowflakeApiClient;
    this.objectMapper = client.getApiClient().getObjectMapper();
  }

  @Override
  public void onAccessActivatedEvent(AccessActivatedEvent event) {
    log.info("Processing AccessActivatedEvent {}", event.getId());
    var access = getAccess(event.getId());
    if (access == null) {
      log.info("Access {} not found, skip granting permissions", event.getId());
      return;
    }
    var server = resolveProviderServer(access);
    if (server == null) {
      log.info("Access {} is not applicable for Snowflake access management", access.getId());
      return;
    }
    if (!isActive(access)) {
      log.info("Access {} is not active, skip granting permissions", access.getId());
      return;
    }
    grantPermissions(access, server);
  }

  @Override
  public void onAccessDeactivatedEvent(AccessDeactivatedEvent event) {
    log.info("Processing AccessDeactivatedEvent {}", event.getId());
    var access = getAccess(event.getId());
    if (access == null) {
      log.info("Access {} not found, skip revoking permissions", event.getId());
      return;
    }
    if (resolveProviderServer(access) == null) {
      log.info("Access {} is not applicable for Snowflake access management", access.getId());
      return;
    }
    revokePermissions(access);
  }

  private boolean isActive(Access access) {
    return Objects.equals(access.getInfo().getActive(), Boolean.TRUE);
  }

  /**
   * Resolves the Snowflake server (database/schema) for the provider output port of this access.
   * The server details live in the linked data contract, with the output port's own server config as
   * fallback. Returns {@code null} when the access is not applicable for this connector, i.e. the output
   * port is not a Snowflake port or no server with database and schema could be resolved.
   */
  private Map<String, String> resolveProviderServer(Access access) {
    var provider = access.getProvider();
    if (provider == null) {
      log.debug("Abort, as no provider is available");
      return null;
    }
    var dataProductId = provider.getDataProductId();
    var outputPortId = provider.getOutputPortId();

    var dataProductMap = objectMapper.convertValue(client.getDataProductsApi().getDataProduct(dataProductId), Map.class);
    @SuppressWarnings("unchecked")
    var outputPort = findOutputPort((Map<String, Object>) dataProductMap, outputPortId);
    if (outputPort == null) {
      log.info("No output port found for dataProductId {}, outputPortId {}", dataProductId, outputPortId);
      return null;
    }

    var type = (String) outputPort.get("type");
    if (type == null || !type.equalsIgnoreCase("snowflake")) {
      log.info("Output port type is not snowflake for dataProductId {}, outputPortId {}", dataProductId, outputPortId);
      return null;
    }

    // Resolve server config: first try data contract, then fall back to direct server field
    var server = resolveServerFromContract(outputPort);
    if (server == null) {
      server = resolveServerFromOutputPort(outputPort);
    }
    if (server == null) {
      log.info("No server could be resolved for dataProductId {}, outputPortId {}", dataProductId, outputPortId);
      return null;
    }

    if (server.get("database") == null || server.get("schema") == null) {
      log.info("Server for dataProductId {}, outputPortId {} has no database or schema", dataProductId, outputPortId);
      return null;
    }

    return server;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> findOutputPort(Map<String, Object> dataProductMap, String outputPortId) {
    var outputPorts = (List<Map<String, Object>>) dataProductMap.get("outputPorts");
    if (outputPorts == null) {
      return null;
    }
    for (var port : outputPorts) {
      var portId = (String) port.get("id");
      var portName = (String) port.get("name");
      if (outputPortId.equals(portId) || outputPortId.equals(portName)) {
        return port;
      }
    }
    return null;
  }

  /**
   * Resolves the server from the ODCS data contract linked by the output port: servers is a list carrying
   * the name in the {@code server} field, and a Snowflake server carries {@code database} and
   * {@code schema}. The contract is fetched untyped to stay independent of the bundled SDK model version.
   */
  @SuppressWarnings("unchecked")
  private Map<String, String> resolveServerFromContract(Map<String, Object> outputPort) {
    // Get the data contract ID - DPS uses "dataContractId", ODPS uses "contractId"
    var dataContractId = (String) outputPort.get("dataContractId");
    if (dataContractId == null) {
      dataContractId = (String) outputPort.get("contractId");
    }
    if (dataContractId == null) {
      // ODPS may store contractId in customProperties
      dataContractId = getCustomPropertyValue(outputPort, "contractId");
    }
    if (dataContractId == null) {
      return null;
    }

    var contractServerName = getOutputPortCustomField(outputPort, "contractServer");

    Map<String, Object> dataContract;
    try {
      dataContract = fetchDataContractAsMap(dataContractId);
    } catch (Exception e) {
      log.warn("Failed to fetch data contract {}: {}", dataContractId, e.getMessage());
      return null;
    }

    var servers = dataContract.get("servers");

    if (servers instanceof Map) {
      log.warn("Data contract {} uses the deprecated DCS format, which is not supported; migrate it to ODCS", dataContractId);
      return null;
    }

    // Open Data Contract Standard (ODCS): servers is a List with "server" field as the name
    if (servers instanceof List) {
      var serversList = (List<Map<String, Object>>) servers;
      Map<String, Object> server;
      if (contractServerName != null) {
        server = serversList.stream()
            .filter(s -> contractServerName.equals(s.get("server")))
            .findFirst().orElse(serversList.isEmpty() ? null : serversList.get(0));
      } else {
        server = serversList.isEmpty() ? null : serversList.get(0);
      }
      if (server != null) {
        return toStringMap(server);
      }
    }

    return null;
  }

  private Map<String, Object> fetchDataContractAsMap(String dataContractId) throws ApiException {
    var apiClient = client.getApiClient();
    var path = "/api/datacontracts/" + apiClient.escapeString(dataContractId);
    return apiClient.invokeAPI(
        path,
        "GET",
        new ArrayList<>(),
        new ArrayList<>(),
        "",
        null,
        new HashMap<>(),
        new HashMap<>(),
        new HashMap<>(),
        "application/json",
        null,
        new String[] {"ApiKeyAuth", "BearerAuth"},
        new TypeReference<Map<String, Object>>() {});
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> resolveServerFromOutputPort(Map<String, Object> outputPort) {
    // DPS format: direct "server" field
    if (outputPort.containsKey("server") && outputPort.get("server") instanceof Map) {
      return toStringMap((Map<String, Object>) outputPort.get("server"));
    }
    // ODPS format: server in customProperties
    if (outputPort.containsKey("customProperties") && outputPort.get("customProperties") instanceof List) {
      for (var prop : (List<Map<String, Object>>) outputPort.get("customProperties")) {
        if ("server".equals(prop.get("property")) && prop.get("value") instanceof Map) {
          return toStringMap((Map<String, Object>) prop.get("value"));
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private String getOutputPortCustomField(Map<String, Object> outputPort, String fieldName) {
    if (outputPort.get("custom") instanceof Map) {
      var custom = (Map<String, Object>) outputPort.get("custom");
      var value = custom.get(fieldName);
      if (value != null) {
        return value.toString();
      }
    }
    return getCustomPropertyValue(outputPort, fieldName);
  }

  @SuppressWarnings("unchecked")
  private String getCustomPropertyValue(Map<String, Object> map, String propertyName) {
    if (map.get("customProperties") instanceof List) {
      for (var prop : (List<Map<String, Object>>) map.get("customProperties")) {
        if (propertyName.equals(prop.get("property"))) {
          var value = prop.get("value");
          return value != null ? value.toString() : null;
        }
      }
    }
    return null;
  }

  private static Map<String, String> toStringMap(Map<String, Object> map) {
    var result = new HashMap<String, String>();
    for (var entry : map.entrySet()) {
      if (entry.getValue() != null) {
        result.put(entry.getKey(), entry.getValue().toString());
      }
    }
    return result;
  }

  void grantPermissions(Access access, Map<String, String> server) {
    var dataProductId = access.getProvider().getDataProductId();
    var outputPortId = access.getProvider().getOutputPortId();
    var snowflakeSchema = getSnowflakeSchema(server);
    var accessRoleName = getAccessRoleName(access);
    var accessDescription = getAccessDescription(access, dataProductId, outputPortId, snowflakeSchema);

    var accessRole = createSnowflakeRole(accessRoleName, accessDescription);

    switch (consumerType(access)) {
      case DATA_PRODUCT -> {
        var consumerDataProductRoleName = getConsumerDataProductRoleName(access);
        var consumerDataProductRole = createSnowflakeRole(consumerDataProductRoleName, "Managed by Entropy Data");
        grantRoleToRole(accessRole.getName(), consumerDataProductRole.getName());

        var teamRoleName = getConsumerTeamRoleName(access);
        var consumerTeamRole = createSnowflakeRole(teamRoleName, "Managed by Entropy Data");
        var consumerTeam = getConsumerTeam(access.getConsumer().getTeamId());
        var consumerTeamMemberEmailAddresses = getMemberEmailAddresses(consumerTeam);
        var consumerTeamMemberUserNames = getSnowflakeUserNames(consumerTeamMemberEmailAddresses);
        grantRoleToUsers(consumerTeamRole, consumerTeamMemberUserNames);
        grantRoleToRole(accessRole.getName(), consumerTeamRole.getName());
      }
      case TEAM -> {
        var teamRoleName = getConsumerTeamRoleName(access);
        var consumerTeamRole = createSnowflakeRole(teamRoleName, "Managed by Entropy Data");
        var consumerTeam = getConsumerTeam(access.getConsumer().getTeamId());
        var consumerTeamMemberEmailAddresses = getMemberEmailAddresses(consumerTeam);
        var consumerTeamMemberUserNames = getSnowflakeUserNames(consumerTeamMemberEmailAddresses);
        grantRoleToUsers(consumerTeamRole, consumerTeamMemberUserNames);
        grantRoleToRole(accessRole.getName(), consumerTeamRole.getName());
      }
      case USER -> {
        var emailAddress = access.getConsumer().getUserId();
        var snowflakeUserNames = getSnowflakeUserNames(List.of(emailAddress));
        grantRoleToUsers(accessRole, snowflakeUserNames);
      }
    }

    grantSchemaPermissions(snowflakeSchema, accessRole.getName());

    // TODO: update access resource in Entropy Data with logs
  }

  private SchemaInfo getSnowflakeSchema(Map<String, String> server) {
    var snowflakeDatabase = server.get("database");
    var snowflakeSchema = server.get("schema");
    return new SchemaApi(snowflakeApiClient).listSchemas(snowflakeDatabase, snowflakeSchema, null, null, null, null).stream()
        .filter(schema -> schema.getName().equals(snowflakeSchema))
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Schema %s.%s not found".formatted(snowflakeDatabase, snowflakeSchema)));
  }

  private String getAccessDescription(Access access, String dataProductId, String outputPortId,
      SchemaInfo snowflakeSchema) {
    return "Entropy Data. Managed Access %s to snowflake schema %s.%s for Data Product %s, Output Port %s"
        .formatted(access.getId(), snowflakeSchema.getDatabaseName(), snowflakeSchema.getName(), dataProductId, outputPortId);
  }

  private List<String> getSnowflakeUserNames(List<String> emailAddresses) {
    log.info("Getting Snowflake user for email addresses: {}", emailAddresses);
    if (emailAddresses == null || emailAddresses.isEmpty()) {
      return Collections.emptyList();
    }
    var filteredEmailAddresses = emailAddresses.stream().filter(email -> email != null && !email.isBlank()).map(String::toLowerCase)
        .toList();
    UserApi userApi = new UserApi(snowflakeApiClient);
    // Use SCIM2 API instead?
    List<User> allUsers = userApi.listUsers(null, null, null, null);
    return allUsers.stream().filter(user -> filteredEmailAddresses.contains(user.getEmail())).map(User::getName).toList();
  }

  private void grantRoleToUsers(Role role, List<String> snowflakeUserNames) {
    UserApi userApi = new UserApi(snowflakeApiClient);
    for (String snowflakeUserName : snowflakeUserNames) {
      log.info("Granting role {} to user {}", role.getName(), snowflakeUserName);
      userApi.grant(snowflakeUserName,
          new snowflake.client.model.user.Grant()
              .securableType("ROLE")
              .securable(new snowflake.client.model.user.Securable().name(role.getName()))
              .addPrivilegesItem("USAGE")
      );
    }
  }

  private static String getAccessRoleName(Access access) {
    if (access.getCustom() != null && access.getCustom().containsKey("snowflakeRole")) {
      return access.getCustom().get("snowflakeRole");
    }
    return "access_" + sanitize(access.getId());
  }

  private String getConsumerDataProductRoleName(Access access) {
    DataProduct consumerDataProduct = getDataProduct(access.getConsumer().getDataProductId());
    if (consumerDataProduct.getCustom() != null && consumerDataProduct.getCustom().containsKey("snowflakeRole")) {
      return consumerDataProduct.getCustom().get("snowflakeRole");
    }
    return "dataproduct_" + sanitize(access.getConsumer().getDataProductId());
  }

  private String getConsumerTeamRoleName(Access access) {
    var consumerTeam = getConsumerTeam(access.getConsumer().getTeamId());
    if (consumerTeam.getCustom() != null && consumerTeam.getCustom().containsKey("snowflakeRole")) {
      return consumerTeam.getCustom().get("snowflakeRole");
    }
    return "team_" + sanitize(access.getConsumer().getTeamId());
  }

  private static String sanitize(String string) {
    return string
        .replaceAll("-", "_")
        .replaceAll("\\.", "_")
        .replaceAll("/", "_")
        .replaceAll("[^a-zA-Z0-9_]", "");
  }

  /**
   * Revoking permissions means simply deleting the Snowflake role for this Access resource.
   */
  private void revokePermissions(Access access) {
    var accessRoleName = getAccessRoleName(access);
    RoleApi roleApi = new RoleApi(snowflakeApiClient);
    log.info("Deleting access role {} for access {}", accessRoleName, access.getId());
    roleApi.deleteRole(accessRoleName, true);
    log.info("Access role {} deleted", accessRoleName);
  }

  private Role createSnowflakeRole(String roleName, String comment) {
    RoleApi roleApi = new RoleApi(snowflakeApiClient);
    var role = getRoleByName(roleName);
    if (role.isPresent()) {
      log.info("Role {} already exists", roleName);
      return role.get();
    }
    log.info("Creating role {}", roleName);
    var newRole = new Role().name(roleName).comment(comment);
    roleApi.createRole(newRole, "ifNotExists");
    log.info("Created role {}", roleName);
    return newRole;
  }

  private Optional<Role> getRoleByName(String roleName) {
    RoleApi roleApi = new RoleApi(snowflakeApiClient);
    List<Role> roles = roleApi.listRoles("roleName", null, null, null);
    return roles.stream().filter(role -> role.getName().equals(roleName)).findFirst();
  }

  protected void grantRoleToRole(String roleName, String parentRoleName) {
    RoleApi roleApi = new RoleApi(snowflakeApiClient);
    log.info("Granting role {} to role {}", roleName, parentRoleName);
    roleApi.grantPrivileges(
        parentRoleName,
        new Grant()
            .securable(new Securable().name(roleName))
            .securableType("ROLE")
            .grantOption(false)
            .addPrivilegesItem("USAGE")
    );
  }

  private Team getConsumerTeam(String teamId) {
    return client.getTeamsApi().getTeam(teamId);
  }

  private static List<String> getMemberEmailAddresses(Team consumerTeam) {
    if (consumerTeam.getMembers() == null) {
      return Collections.emptyList();
    }
    return consumerTeam.getMembers().stream().map(TeamMembersInner::getEmailAddress).toList();
  }


  private ConsumerType consumerType(Access access) {
    //noinspection ConstantValue
    if (access.getConsumer().getDataProductId() != null) {
      return ConsumerType.DATA_PRODUCT;
    } else if (access.getConsumer().getTeamId() != null) {
      return ConsumerType.TEAM;
    } else if (access.getConsumer().getUserId() != null) {
      return ConsumerType.USER;
    }
    throw new IllegalArgumentException("Unknown consumer type");
  }

  enum ConsumerType {
    DATA_PRODUCT,
    TEAM,
    USER
  }

  public void grantSchemaPermissions(SchemaInfo schemaInfo, String roleName) {
    RoleApi roleApi = new RoleApi(snowflakeApiClient);
    var databaseName = schemaInfo.getDatabaseName();
    var schemaName = schemaInfo.getName();

    log.info("Granting USAGE permission to role {} on schema {}.{}", roleName, databaseName, schemaName);
    roleApi.grantPrivileges(
        roleName,
        new Grant()
            .securableType("SCHEMA")
            .containingScope(new ContainingScope().database(databaseName))
            .addPrivilegesItem("USAGE")
    );

    roleApi.grantPrivileges(
        roleName,
        new Grant()
            .securableType("TABLE")
            .containingScope(new ContainingScope().database(databaseName).schema(schemaName))
            .addPrivilegesItem("SELECT"));

    roleApi.grantFuturePrivileges(
        roleName,
        new Grant()
            .securableType("TABLE")
            .containingScope(new ContainingScope().database(databaseName).schema(schemaName))
            .addPrivilegesItem("SELECT"));

    roleApi.grantPrivileges(
        roleName,
        new Grant()
            .securableType("VIEW")
            .containingScope(new ContainingScope().database(databaseName).schema(schemaName))
            .addPrivilegesItem("SELECT"));

    roleApi.grantFuturePrivileges(
        roleName,
        new Grant()
            .securableType("VIEW")
            .containingScope(new ContainingScope().database(databaseName).schema(schemaName))
            .addPrivilegesItem("SELECT"));

    // TODO return log information
  }

  private Access getAccess(String accessId) {
    try {
      return client.getAccessApi().getAccess(accessId);
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        log.info("Access {} not found", accessId);
        return null;
      } else {
        log.error("Error getting access", e);
        throw e;
      }
    }
  }

  private DataProduct getDataProduct(String dataProductId) {
    try {
      return objectMapper.convertValue(client.getDataProductsApi().getDataProduct(dataProductId), DataProduct.class);
    } catch (ApiException e) {
      log.error("Error getting data product", e);
      throw new RuntimeException(e);
    }
  }

}
