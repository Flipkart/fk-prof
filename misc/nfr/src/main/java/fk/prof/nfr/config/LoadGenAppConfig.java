package fk.prof.nfr.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class LoadGenAppConfig extends Configuration {

    @NotNull
    @Valid
    @JsonProperty("database")
    private DataSourceFactory database = new DataSourceFactory();

    @NotNull
    @JsonProperty("serversList")
    private List<InetSocketAddress> servers;

    @NotNull
    @JsonProperty("enableCpuWork")
    private Boolean enableCpuWork;

    @NotNull
    @JsonProperty("enableIoWork")
    private Boolean enableIoWork;

    @Nullable
    @JsonProperty("ioWork")
    private IoWorkConfig ioWorkConfig;

    @Nullable
    @JsonProperty("cpuWork")
    private CpuWorkConfig cpuWorkConfig;

    @Nullable
    @JsonProperty("threadSpawnWork")
    private ThreadSpawnerConfig threadSpawnConfig;

    @JsonProperty("isDebug")
    private Boolean isDebug;

    public DataSourceFactory getDataSourceFactory() {
        return database;
    }

    public IoWorkConfig getIoWorkConfig() {
        return ioWorkConfig;
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

    public List<InetSocketAddress> getServers() {
        return servers;
    }

    public void setServers(List<String> instances) {
        this.servers = instances.stream().map(s -> {
            String[] ipPortPair = s.split(":");
            return InetSocketAddress.createUnresolved(ipPortPair[0], Integer.parseInt(ipPortPair[1]));
        }).collect(Collectors.toList());
    }
}
