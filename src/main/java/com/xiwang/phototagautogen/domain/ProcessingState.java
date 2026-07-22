package com.xiwang.phototagautogen.domain;

import java.time.Instant;
import java.util.UUID;

public record ProcessingState(UUID assetId, String fileModifiedAt, String model, String promptVersion,
                              int taxonomyVersion, String status, Instant processedAt, String errorCode) {}
