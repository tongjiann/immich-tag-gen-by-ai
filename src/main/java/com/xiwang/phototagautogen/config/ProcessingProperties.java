package com.xiwang.phototagautogen.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "processing")
@Validated
public class ProcessingProperties {

    @Min(1)
    @Max(1000)
    private int pageSize = 100;

    @Min(0)
    @Max(10)
    private int maxRetries = 3;

    @Min(1)
    @Max(8)
    private int concurrency = 2;

    @NotBlank
    private String stateFile = ".data/processing-state.jsonl";
    private boolean force;
    private boolean dryRun;
    private String assetId;
    private String skipAlbums;
    @Min(1)
    private int httpTimeoutSeconds = 30;

    @Min(1)
    private int modelTimeoutSeconds = 300;

    @Min(1)
    private int connectTimeoutSeconds = 5;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private BigDecimal confidenceThreshold = new BigDecimal("0.65");

    @NotBlank
    private String promptVersion = "v3";

    @Min(1)
    private int modelReleaseInterval = 50;

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public String getStateFile() { return stateFile; }
    public void setStateFile(String stateFile) { this.stateFile = stateFile; }
    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getSkipAlbums() { return skipAlbums; }
    public void setSkipAlbums(String skipAlbums) { this.skipAlbums = skipAlbums; }
    public int getHttpTimeoutSeconds() { return httpTimeoutSeconds; }
    public void setHttpTimeoutSeconds(int httpTimeoutSeconds) { this.httpTimeoutSeconds = httpTimeoutSeconds; }
    public int getModelTimeoutSeconds() { return modelTimeoutSeconds; }
    public void setModelTimeoutSeconds(int modelTimeoutSeconds) { this.modelTimeoutSeconds = modelTimeoutSeconds; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public BigDecimal getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(BigDecimal confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public int getModelReleaseInterval() { return modelReleaseInterval; }
    public void setModelReleaseInterval(int modelReleaseInterval) { this.modelReleaseInterval = modelReleaseInterval; }
}
