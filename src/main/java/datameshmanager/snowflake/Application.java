package datameshmanager.snowflake;

import datameshmanager.sdk.DataMeshManagerAssetsSynchronizer;
import datameshmanager.sdk.DataMeshManagerClient;
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
    snowflakeApiClient.setBasePath("https://%s.snowflakecomputing.com".formatted(snowflakeProperties.account()));
    snowflakeApiClient.addDefaultHeader("X-Snowflake-Authorization-Token-Type", "KEYPAIR_JWT");
    return snowflakeApiClient;
  }


  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "datameshmanager.client.snowflake.assets.enabled", havingValue = "true")
  public DataMeshManagerAssetsSynchronizer dataMeshManagerAssetsSynchronizer(
      SnowflakeProperties snowflakeProperties,
      DataMeshManagerClient client,
      ApiClient snowflakeApiClient,
      TaskExecutor taskExecutor) {
    var agentId = snowflakeProperties.assets().agentid();
    var assetsProvider = new SnowflakeAssetsProvider(snowflakeProperties, snowflakeApiClient);
    var dataMeshManagerAssetsSynchronizer = new DataMeshManagerAssetsSynchronizer(agentId, client, assetsProvider);
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
