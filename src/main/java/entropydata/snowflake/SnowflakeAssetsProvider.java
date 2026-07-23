package entropydata.snowflake;

import entropydata.sdk.EntropyDataAssetsProvider;
import entropydata.sdk.client.model.Asset;
import entropydata.sdk.client.model.AssetColumn;
import entropydata.sdk.client.model.AssetInfo;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import snowflake.client.ApiClient;
import snowflake.client.api.DatabaseApi;
import snowflake.client.api.SchemaApi;
import snowflake.client.api.TableApi;
import snowflake.client.api.ViewApi;
import snowflake.client.model.database.Database;
import snowflake.client.model.schema.SchemaInfo;
import snowflake.client.model.table.Table;
import snowflake.client.model.view.View;

public class SnowflakeAssetsProvider implements EntropyDataAssetsProvider {

  private static final Logger log = LoggerFactory.getLogger(SnowflakeAssetsProvider.class);

  private final SnowflakeProperties snowflakeProperties;
  private final ApiClient snowflakeApiClient;

  public SnowflakeAssetsProvider(SnowflakeProperties snowflakeProperties, ApiClient snowflakeApiClient) {
    this.snowflakeProperties = snowflakeProperties;
    this.snowflakeApiClient = snowflakeApiClient;
  }

  @Override
  public void fetchAssets(AssetCallback assetCallback) {
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
            .qualifiedName(schema.getDatabaseName() + "." + schema.getName())
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
            .qualifiedName(table.getDatabaseName() + "." + table.getSchemaName() + "." + table.getName())
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
        asset.addColumnsItem(new AssetColumn()
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
            .qualifiedName(view.getDatabaseName() + "." + view.getSchemaName() + "." + view.getName())
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
        asset.addColumnsItem(new AssetColumn()
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

}
