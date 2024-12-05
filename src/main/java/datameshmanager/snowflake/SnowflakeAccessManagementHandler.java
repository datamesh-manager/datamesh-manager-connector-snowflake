package datameshmanager.snowflake;

import datameshmanager.sdk.DataMeshManagerClient;
import datameshmanager.sdk.DataMeshManagerEventHandler;
import datameshmanager.sdk.client.ApiException;
import datameshmanager.sdk.client.model.Access;
import datameshmanager.sdk.client.model.AccessActivatedEvent;
import datameshmanager.sdk.client.model.AccessDeactivatedEvent;
import datameshmanager.sdk.client.model.DataProduct;
import datameshmanager.sdk.client.model.DataProductOutputPortsInner;
import datameshmanager.sdk.client.model.DataProductOutputPortsInnerServer;
import datameshmanager.sdk.client.model.Team;
import datameshmanager.sdk.client.model.TeamMembersInner;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
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

public class SnowflakeAccessManagementHandler implements DataMeshManagerEventHandler {

  private static final Logger log = LoggerFactory.getLogger(SnowflakeAccessManagementHandler.class);

  private final DataMeshManagerClient client;
  private final ApiClient snowflakeApiClient;

  public SnowflakeAccessManagementHandler(
      DataMeshManagerClient client, ApiClient snowflakeApiClient) {
    this.client = client;
    this.snowflakeApiClient = snowflakeApiClient;
  }

  @Override
  public void onAccessActivatedEvent(AccessActivatedEvent event) {
    log.info("Processing AccessActivatedEvent {}", event.getId());
    var access = getAccess(event.getId());
    if (!isApplicable(access)) {
      log.info("Access {} is not applicable for Snowflake access management", access.getId());
      return;
    }
    if (!isActive(access)) {
      log.info("Access {} is not active, skip granting permissions", access.getId());
      return;
    }
    grantPermissions(access);
  }

  @Override
  public void onAccessDeactivatedEvent(AccessDeactivatedEvent event) {
    log.info("Processing AccessDeactivatedEvent {}", event.getId());
    var access = getAccess(event.getId());
    if (!isApplicable(access)) {
      log.info("Access {} is not applicable for Snowflake access management", access.getId());
      return;
    }
    revokePermissions(access);
  }

  private boolean isApplicable(Access access) {
    var dataProductId = access.getProvider().getDataProductId();
    var dataProduct = getDataProduct(dataProductId);
    var outputPortId = access.getProvider().getOutputPortId();
    var outputPort = getOutputPort(dataProduct, outputPortId);
    var server = outputPort.getServer();
    if (outputPort.getType() == null || !Objects.equals(outputPort.getType().toLowerCase(), "snowflake")) {
      log.info("Output port type is not Snowflake for dataProductId {}, outputPortId: {}", dataProductId, outputPortId);
      return false;
    }
    if (server == null) {
      log.warn("Server is undefined for dataProductId {}, outputPortId: {}", dataProductId, outputPortId);
      return false;
    }

    return true;
  }

  private boolean isActive(Access access) {
    return Objects.equals(access.getInfo().getActive(), Boolean.TRUE);
  }

  void grantPermissions(Access access) {
    var dataProductId = access.getProvider().getDataProductId();
    var dataProduct = getDataProduct(dataProductId);
    var outputPortId = access.getProvider().getOutputPortId();
    var outputPort = getOutputPort(dataProduct, outputPortId);
    var snowflakeSchema = getSchemaFromOutputPort(outputPort, dataProductId);
    var accessRoleName = getAccessRoleName(access);
    var accessDescription = getAccessDescription(access, dataProduct, outputPort, snowflakeSchema);

    var accessRole = createSnowflakeRole(accessRoleName, accessDescription);

    switch (consumerType(access)) {
      case DATA_PRODUCT -> {
        var consumerDataProductRoleName = getConsumerDataProductRoleName(access);
        var consumerDataProductRole = createSnowflakeRole(consumerDataProductRoleName, "Managed by Data Mesh Manager");
        grantRoleToRole(accessRole.getName(), consumerDataProductRole.getName());

        var teamRoleName = getConsumerTeamRoleName(access);
        var consumerTeamRole = createSnowflakeRole(teamRoleName, "Managed by Data Mesh Manager");
        var consumerTeam = getConsumerTeam(access.getConsumer().getTeamId());
        var consumerTeamMemberEmailAddresses = getMemberEmailAddresses(consumerTeam);
        var consumerTeamMemberUserNames = getSnowflakeUserNames(consumerTeamMemberEmailAddresses);
        grantRoleToUsers(consumerTeamRole, consumerTeamMemberUserNames);
        grantRoleToRole(accessRole.getName(), consumerTeamRole.getName());
      }
      case TEAM -> {
        var teamRoleName = getConsumerTeamRoleName(access);
        var consumerTeamRole = createSnowflakeRole(teamRoleName, "Managed by Data Mesh Manager");
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

    // TODO: update access resource in Data Mesh Manager with logs
  }

  private String getAccessDescription(Access access, DataProduct dataProduct, DataProductOutputPortsInner outputPort,
      SchemaInfo snowflakeSchema) {
    return "Data Mesh Manager. Managed Access %s to snowflake schema %s.%s for Data Product %s, Output Port %s"
        .formatted(snowflakeSchema.getDatabaseName(), access.getId(), snowflakeSchema.getName(), dataProduct.getId(), outputPort.getId());
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

  @NotNull
  private static String getAccessRoleName(Access access) {
    if (access.getCustom() != null && access.getCustom().containsKey("snowflakeRole")) {
      return access.getCustom().get("snowflakeRole");
    }
    return "access_" + sanitize(access.getId());
  }

  @NotNull
  private String getConsumerDataProductRoleName(Access access) {
    DataProduct consumerDataProduct = getDataProduct(access.getConsumer().getDataProductId());
    if (consumerDataProduct.getCustom() != null && consumerDataProduct.getCustom().containsKey("snowflakeRole")) {
      return consumerDataProduct.getCustom().get("snowflakeRole");
    }
    return "dataproduct_" + sanitize(access.getConsumer().getDataProductId());
  }

  @NotNull
  private String getConsumerTeamRoleName(Access access) {
    var consumerTeam = getConsumerTeam(access.getConsumer().getTeamId());
    if (consumerTeam.getCustom() != null && consumerTeam.getCustom().containsKey("snowflakeRole")) {
      return consumerTeam.getCustom().get("snowflakeRole");
    }
    return "team_" + sanitize(access.getConsumer().getTeamId());
  }

  @NotNull
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


  private DataProductOutputPortsInner getOutputPort(DataProduct dataProduct, String outputPortId) {
    return dataProduct.getOutputPorts()
        .stream()
        .filter(outputPort -> outputPort.getId().equals(outputPortId))
        .findFirst().orElse(null);
  }

  private SchemaInfo getSchemaFromOutputPort(DataProductOutputPortsInner outputPort, String dataProductId) {
    var server = getServer(outputPort, dataProductId);
    var snowflakeDatabase = server.get("database");
    var snowflakeSchema = server.get("schema");

    if (snowflakeDatabase == null || snowflakeDatabase.isBlank()) {
      throw new RuntimeException(
          "The server field database is not defined for data product %s in output port %s".formatted(dataProductId, outputPort.getId()));
    }

    if (snowflakeSchema == null || snowflakeSchema.isBlank()) {
      throw new RuntimeException(
          "The server field schema is not defined for data product %s in output port %s".formatted(dataProductId, outputPort.getId()));
    }

    return new SchemaApi(snowflakeApiClient).listSchemas(snowflakeDatabase, snowflakeSchema, null, null, null, null).stream()
        .filter(schema -> schema.getName().equals(snowflakeSchema))
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Schema %s.%s not found".formatted(snowflakeDatabase, snowflakeSchema)));
  }

  private DataProductOutputPortsInnerServer getServer(DataProductOutputPortsInner outputPort, String dataProductId) {
    var server = outputPort.getServer();
    validateServer(server, dataProductId, outputPort.getId());
    return server;
  }

  private void validateServer(DataProductOutputPortsInnerServer server, String dataProductId, String outputPortId) {
    if (server == null) {
      log.error("Server is null for dataProductId {}, outputPortId: {}", dataProductId, outputPortId);
      throw new RuntimeException("Server does not exist for dataProductId " + dataProductId + " and outputPortId " + outputPortId);
    }
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
    return client.getAccessApi().getAccess(accessId);
  }

  private DataProduct getDataProduct(String dataProductId) {
    try {
      return client.getDataProductsApi().getDataProduct(dataProductId);
    } catch (ApiException e) {
      log.error("Error getting data product", e);
      throw new RuntimeException(e);
    }
  }

}
