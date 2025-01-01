package datameshmanager.snowflake;

import datameshmanager.sdk.DataMeshManagerAssetsSynchronizer;
import datameshmanager.sdk.DataMeshManagerClient;
import datameshmanager.sdk.DataMeshManagerEventListener;
import datameshmanager.sdk.DataMeshManagerStateRepositoryRemote;
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

@SpringBootApplication(scanBasePackages = "datameshmanager")
@ConfigurationPropertiesScan("datameshmanager")
@EnableScheduling
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public DataMeshManagerClient dataMeshManagerClient(
      @Value("${datameshmanager.client.host}") String host,
      @Value("${datameshmanager.client.apikey}") String apiKey) {
    return new DataMeshManagerClient(host, apiKey);
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
  @ConditionalOnProperty(value = "datameshmanager.client.snowflake.accessmanagement.enabled", havingValue = "true")
  public DataMeshManagerEventListener dataMeshManagerEventListener(
      DataMeshManagerClient client,
      SnowflakeProperties snowflakeProperties,
      ApiClient snowflakeApiClient,
      TaskExecutor taskExecutor) {
    var connectorId = snowflakeProperties.accessmanagement().connectorid();
    var eventHandler = new SnowflakeAccessManagementHandler(client, snowflakeApiClient);
    var stateRepository = new DataMeshManagerStateRepositoryRemote(connectorId, client);
    var dataMeshManagerEventListener = new DataMeshManagerEventListener(connectorId, "accessmanagement", client, eventHandler, stateRepository);
    taskExecutor.execute(dataMeshManagerEventListener::start);
    return dataMeshManagerEventListener;
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "datameshmanager.client.snowflake.assets.enabled", havingValue = "true")
  public DataMeshManagerAssetsSynchronizer dataMeshManagerAssetsSynchronizer(
      SnowflakeProperties snowflakeProperties,
      DataMeshManagerClient client,
      ApiClient snowflakeApiClient,
      TaskExecutor taskExecutor) {
    var connectorId = snowflakeProperties.assets().connectorid();
    var assetsProvider = new SnowflakeAssetsProvider(snowflakeProperties, snowflakeApiClient);
    var dataMeshManagerAssetsSynchronizer = new DataMeshManagerAssetsSynchronizer(connectorId, client, assetsProvider);
    if (snowflakeProperties.assets().pollinterval() != null) {
      dataMeshManagerAssetsSynchronizer.setDelay(snowflakeProperties.assets().pollinterval());
    }

    taskExecutor.execute(dataMeshManagerAssetsSynchronizer::start);
    return dataMeshManagerAssetsSynchronizer;
  }

  @Bean
  public SimpleAsyncTaskExecutor taskExecutor() {
    return new SimpleAsyncTaskExecutor();
  }

}
