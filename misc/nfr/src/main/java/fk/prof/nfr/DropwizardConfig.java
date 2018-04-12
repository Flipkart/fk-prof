package fk.prof.nfr;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.db.DataSourceFactory;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.ValidHost;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.ValidPort;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class DropwizardConfig extends Configuration {

    @Valid
    @NotNull
    @JsonProperty("database")
    private DataSourceFactory database = new DataSourceFactory();

    public DataSourceFactory getDataSourceFactory() {
        return database;
    }

    @ValidHost
    @NotNull
    @JsonProperty("driver_ip")
    private String driverIp;

    @ValidPort
    @NotNull
    @JsonProperty("driver_port")
    private Integer driverPort;

    public String getDriverIp() {
        return driverIp;
    }

    public Integer getDriverPort() {
        return driverPort;
    }

    @ValidHost
    @NotNull
    @JsonProperty("app_ip")
    private String appIp;

    @ValidPort
    @NotNull
    @JsonProperty("app_port")
    private Integer appPort;

    public String getAppIp() {
        return appIp;
    }

    public Integer getAppPort() {
        return appPort;
    }

    @NotNull
    @JsonProperty("driver")
    private Boolean isDriver;

    public Boolean isDriver() {
        return isDriver;
    }

    @Valid
    @NotNull
    private HttpClientConfiguration httpClient = new HttpClientConfiguration();

    @JsonProperty("httpClient")
    public HttpClientConfiguration getHttpClientConfiguration() {
        return httpClient;
    }

    @JsonProperty("httpClient")
    public void setHttpClientConfiguration(HttpClientConfiguration httpClient) {
        this.httpClient = httpClient;
    }
}
