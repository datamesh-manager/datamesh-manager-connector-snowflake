package datameshmanager.snowflake;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import datameshmanager.sdk.DataMeshManagerAssetsProvider;
import datameshmanager.sdk.client.model.Asset;
import datameshmanager.sdk.client.model.AssetColumnsInner;
import datameshmanager.sdk.client.model.AssetInfo;
import java.security.spec.RSAPublicKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import snowflake.client.ApiClient;
import snowflake.client.api.DatabaseApi;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.TimeUnit;
import snowflake.client.api.SchemaApi;
import snowflake.client.api.TableApi;
import snowflake.client.api.ViewApi;
import snowflake.client.model.Database;
import snowflake.client.model.SchemaInfo;
import snowflake.client.model.Table;
import snowflake.client.model.View;

public class SnowflakeAssetsProvider implements DataMeshManagerAssetsProvider {

  private static final Logger log = LoggerFactory.getLogger(SnowflakeAssetsProvider.class);

  private final SnowflakeProperties snowflakeProperties;
  private final ApiClient snowflakeApiClient;

  public SnowflakeAssetsProvider(SnowflakeProperties snowflakeProperties, ApiClient snowflakeApiClient) {
    this.snowflakeProperties = snowflakeProperties;
    this.snowflakeApiClient = snowflakeApiClient;
  }

  @Override
  public void fetchAssets(AssetCallback assetCallback) {
    String account = snowflakeProperties.account();
    String user = snowflakeProperties.user();
    File privateKeyFile = snowflakeProperties.privatekeyfile();

    log.info("Generating JWT token for account {} and user {}", account, user);
    String bearerToken = generateJWT(account, user, privateKeyFile);
    snowflakeApiClient.setBearerToken(bearerToken);

    log.info("Calling Snowflake REST API to fetch databases");
    DatabaseApi databaseApi = new DatabaseApi(snowflakeApiClient);
    List<Database> databases = databaseApi.listDatabases(null, null, null, null, null);

    for (var database : databases) {
      if (!includeDatabase(database)) {
        continue;
      }

      log.info("Synchronizing database {}", database.getName());

      var schemaApi = new SchemaApi(snowflakeApiClient);
      var schemas = schemaApi.listSchemas(database.getName(), null, null, null, null, true);


      for (var schema : schemas) {
        if (!includeSchema(schema)) {
          continue;
        }

        log.info("Synchronizing schema {}", toId(schema));

        if (schema.getDroppedOn() != null) {
          assetCallback.onAssetDeleted(toId(schema));
          continue;
        }

        schemaToAsset(schema).ifPresent(assetCallback::onAssetUpdated);

        var tableApi = new TableApi(snowflakeApiClient);
        var tables = tableApi.listTables(database.getName(), schema.getName(), null, null, null, null, true, true);
        for (var table : tables) {
          log.info("Synchronizing table {}", toId(table));
          if (table.getDroppedOn() != null) {
            assetCallback.onAssetDeleted(toId(table));
            continue;
          }
          tableToAsset(table).ifPresent(assetCallback::onAssetUpdated);
        }

        var viewApi = new ViewApi(snowflakeApiClient);
        var views = viewApi.listViews(database.getName(), schema.getName(), null, null, null, null, true);
        for (var view : views) {
          log.info("Synchronizing view {}", toId(view));
          viewToAsset(view).ifPresent(assetCallback::onAssetUpdated);
        }

      }


    }
  }

  protected Optional<Asset> schemaToAsset(SchemaInfo schema) {

    if (!includeSchema(schema)) {
      log.debug("Skipping schema {}", schema.getName());
      return Optional.empty();
    }

    Asset asset = new Asset()
        .id(toId(schema))
        .info(new AssetInfo()
            .name(schema.getName())
            .source("snowflake")
            .qualifiedName(schema.getDatabaseName()+"."+schema.getName())
            .type("snowflake_schema")
            .status("active")
            .description(schema.getComment()))
        .putPropertiesItem("account", snowflakeProperties.account())
        .putPropertiesItem("createdOn", schema.getCreatedOn() != null ? schema.getCreatedOn().toString() : "")
        .putPropertiesItem("kind", schema.getKind() != null ? schema.getKind().getValue() : null)
        .putPropertiesItem("database", schema.getDatabaseName())
        .putPropertiesItem("schema", schema.getName())
        .putPropertiesItem("owner", schema.getOwner());

    return Optional.of(asset);
  }


  protected Optional<Asset> tableToAsset(Table table) {
    if (!includeTable(table)) {
      log.debug("Skipping table {}", table.getName());
      return Optional.empty();
    }

    Asset asset = new Asset()
        .id(toId(table))
        .info(new AssetInfo()
            .name(table.getName())
            .source("snowflake")
            .qualifiedName(table.getDatabaseName()+"."+table.getSchemaName()+"."+table.getName())
            .type("snowflake_table")
            .status("active")
            .description(table.getComment()))
        .putPropertiesItem("account", snowflakeProperties.account())
        .putPropertiesItem("createdOn", table.getCreatedOn() != null ? table.getCreatedOn().toString() : "")
        .putPropertiesItem("kind", table.getKind() != null ? table.getKind().getValue() : null)
        .putPropertiesItem("database", table.getDatabaseName())
        .putPropertiesItem("schema", table.getSchemaName())
        .putPropertiesItem("table", table.getName())
        .putPropertiesItem("tableType", table.getTableType() != null ? table.getTableType().getValue() : null)
        .putPropertiesItem("owner", table.getOwner());

    if (table.getColumns() != null) {
      for (var column : table.getColumns()) {
        asset.addColumnsItem(new AssetColumnsInner()
            .name(column.getName())
            .type(column.getDatatype())
            .description(column.getComment()));
      }
    }

    return Optional.of(asset);
  }

  protected Optional<Asset> viewToAsset(View view) {
    if (!includeView(view)) {
      log.debug("Skipping view {}", view.getName());
      return Optional.empty();
    }

    Asset asset = new Asset()
        .id(toId(view))
        .info(new AssetInfo()
            .name(view.getName())
            .source("snowflake")
            .qualifiedName(view.getDatabaseName()+"."+view.getSchemaName()+"."+view.getName())
            .type("snowflake_view")
            .status("active")
            .description(view.getComment()))
        .putPropertiesItem("account", snowflakeProperties.account())
        .putPropertiesItem("createdOn", view.getCreatedOn() != null ? view.getCreatedOn().toString() : "")
        .putPropertiesItem("kind", view.getKind() != null ? view.getKind().getValue() : null)
        .putPropertiesItem("database", view.getDatabaseName())
        .putPropertiesItem("schema", view.getSchemaName())
        .putPropertiesItem("view", view.getName())
        .putPropertiesItem("secure", view.getSecure())
        .putPropertiesItem("owner", view.getOwner());

    if (view.getColumns() != null) {
      for (var column : view.getColumns()) {
        asset.addColumnsItem(new AssetColumnsInner()
            .name(column.getName())
            .type(column.getDatatype())
            .description(column.getComment()));
      }
    }

    return Optional.of(asset);
  }


  private String toId(SchemaInfo schema) {
    return "snowflake-%s-%s-%s".formatted(snowflakeProperties.account(), schema.getDatabaseName(), schema.getName());
  }

  private String toId(Table table) {
    return "snowflake-%s-%s-%s-%s".formatted(snowflakeProperties.account(), table.getDatabaseName(), table.getName(), table.getName());
  }

  private String toId(View view) {
    return "snowflake-%s-%s-%s-%s".formatted(snowflakeProperties.account(), view.getDatabaseName(), view.getName(), view.getName());
  }

  protected boolean includeDatabase(Database database) {
    if (Objects.equals(database.getName(), "SNOWFLAKE_SAMPLE_DATA")) {
      return false;
    }
    if (Objects.equals(database.getName(), "SNOWFLAKE")) {
      return false;
    }

    return true;
  }

  protected boolean includeSchema(SchemaInfo schema) {
    if (Objects.equals(schema.getName(), "INFORMATION_SCHEMA")) {
      return false;
    }
    return true;
  }

  protected boolean includeTable(Table table) {
    return true;
  }
  protected boolean includeView(View view) {
    return true;
  }

  public static String generateJWT(String account, String user, File privateKeyFile) {
    try {
      var privateKey = readPrivateKey(privateKeyFile);
      RSAPublicKeySpec publicKeySpec =
          new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
      Algorithm algorithm = Algorithm.RSA256(publicKey, privateKey);

      var qualifiedUserName =
          account.toUpperCase(Locale.ROOT)
              + "."
              + user.toUpperCase(Locale.ROOT);

      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      var publicKeyFp =
          "SHA256:" + Base64.getEncoder().encodeToString(digest.digest(publicKey.getEncoded()));

      var issuedTs = new Date();
      var expiresTs = new Date(issuedTs.getTime() + TimeUnit.HOURS.toMillis(1));
      return JWT.create()
          .withIssuer(qualifiedUserName + "." + publicKeyFp)
          .withSubject(qualifiedUserName)
          .withIssuedAt(issuedTs)
          .withExpiresAt(expiresTs)
          .sign(algorithm);
    } catch (Exception e) {
      throw new RuntimeException("Failed to generate JWT", e);
    }
  }

  /**
   * Creates a RSA private key from a P8 file
   *
   * @param file a private key P8 file
   * @return RSAPrivateCrtKey instance
   * @throws Exception arises if any error occurs
   */
  private static RSAPrivateCrtKey readPrivateKey(File file) throws Exception {
    String key = Files.readString(file.toPath(), Charset.defaultCharset());

    String privateKeyPEM =
        key.replace("-----BEGIN PRIVATE KEY-----", "") // pragma: allowlist secret
            .replaceAll(System.lineSeparator(), "")
            .replace("-----END PRIVATE KEY-----", "");

    byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);

    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
    return (RSAPrivateCrtKey) keyFactory.generatePrivate(keySpec);
  }

}
