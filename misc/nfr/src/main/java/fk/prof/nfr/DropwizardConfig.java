package fk.prof.nfr;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
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
    @JsonProperty("client_ip")
    private String clientIp;

    @ValidPort
    @NotNull
    @JsonProperty("client_port")
    private Integer clientPort;

    public String getClientIp() {
        return clientIp;
    }

    public Integer getClientPort() {
        return clientPort;
    }

    @NotNull
    @JsonProperty("driver")
    private Boolean isDriver;

    public Boolean isDriver() {
        return isDriver;
    }
}
