package fk.prof.nfr.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.db.DataSourceFactory;
import javax.annotation.Nullable;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.ValidHost;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.ValidPort;

public class LoadGenAppConfig extends Configuration {

    @Valid
    @NotNull
    @JsonProperty("database")
    private DataSourceFactory database = new DataSourceFactory();

    @ValidHost
    @NotNull
    @JsonProperty("driverIp")
    private String driverIp;

    @ValidPort
    @NotNull
    @JsonProperty("driverPort")
    private Integer driverPort;

    @ValidHost
    @NotNull
    @JsonProperty("appIp")
    private String appIp;

    @ValidPort
    @NotNull
    @JsonProperty("appPort")
    private Integer appPort;

    @NotNull
    @JsonProperty("driver")
    private Boolean isDriver;

    @NotNull
    @JsonProperty("enableCpuWork")
    private Boolean enableCpuWork;

    @NotNull
    @JsonProperty("enableIoWork")
    private Boolean enableIoWork;

    @Nullable
    @JsonProperty("cpuWork")
    private CpuWorkConfig cpuWorkConfig;

    @Nullable
    @JsonProperty("threadSpawnWork")
    private ThreadSpawnerConfig threadSpawnConfig;

    @JsonProperty("isDebug")
    private Boolean isDebug;

    @Valid
    @NotNull
    private HttpClientConfiguration httpClient = new HttpClientConfiguration();

    public DataSourceFactory getDataSourceFactory() {
        return database;
    }

    public String getDriverIp() {
        return driverIp;
    }

    public Integer getDriverPort() {
        return driverPort;
    }

    public String getAppIp() {
        return appIp;
    }

    public Integer getAppPort() {
        return appPort;
    }

    public Boolean isDriver() {
        return isDriver;
    }

    @JsonProperty("httpClient")
    public HttpClientConfiguration getHttpClientConfiguration() {
        return httpClient;
    }

    @JsonProperty("httpClient")
    public void setHttpClientConfiguration(HttpClientConfiguration httpClient) {
        this.httpClient = httpClient;
    }

    public CpuWorkConfig getCpuWorkConfig() {
        return cpuWorkConfig;
    }

    public ThreadSpawnerConfig getThreadSpawnConfig() {
        return threadSpawnConfig;
    }

    public Boolean getEnableCpuWork() {
        return enableCpuWork;
    }

    public Boolean getEnableIoWork() {
        return enableIoWork;
    }

    public Boolean getDebug() {
        return isDebug;
    }
}
