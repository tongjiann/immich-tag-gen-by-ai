package com.xiwang.phototagautogen;

import com.xiwang.phototagautogen.client.ImmichClient;
import com.xiwang.phototagautogen.client.VisionModelClient;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.ProcessingSummary;
import com.xiwang.phototagautogen.service.PhotoProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@Order(1)
public class PhotoTagCommandLineRunner implements CommandLineRunner, ExitCodeGenerator {
    private final ImmichClient immichClient;
    private final VisionModelClient visionModelClient;
    private final PhotoProcessingService processingService;
    private final ProcessingProperties properties;
    private int exitCode;

    public PhotoTagCommandLineRunner(ImmichClient immichClient, VisionModelClient visionModelClient,
                                     PhotoProcessingService processingService, ProcessingProperties properties) {
        this.immichClient = immichClient;
        this.visionModelClient = visionModelClient;
        this.processingService = processingService;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        try {
            boolean force = properties.isForce() || hasBooleanOption(args, "processing.force");
            boolean dryRun = properties.isDryRun() || hasBooleanOption(args, "processing.dry-run");
            UUID assetId = parseAssetId(args);

            log.info("开始验证 Immich 和视觉模型接口连接，model={}", visionModelClient.modelName());
            immichClient.validateConnection();
            visionModelClient.validateConnection();
            log.info("连接验证完成，force={}, dryRun={}", force, dryRun);

            ProcessingSummary summary = processingService.run(force, dryRun, assetId);
            log.info("批处理完成：{}", summary);
            exitCode = summary.failures() == 0 ? 0 : 2;
        } catch (Exception e) {
            exitCode = 1;
            log.error("批处理启动或执行失败：{}", e.getMessage(), e);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private UUID parseAssetId(String[] args) {
        String configured = properties.getAssetId();
        for (String arg : args) {
            if (arg.startsWith("--processing.asset-id=")) {
                configured = arg.substring("--processing.asset-id=".length());
            }
        }
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return UUID.fromString(configured);
    }

    private boolean hasBooleanOption(String[] args, String name) {
        for (String arg : args) {
            if (("--" + name).equals(arg) || ("--" + name + "=true").equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }
}
