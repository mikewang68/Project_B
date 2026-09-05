package com.bproject.safety.module.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 摄像头健康台账（Demo：真实摄像头接入后替换，不提前做视频流）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CameraInfo(String cameraId, String name, String area, boolean online,
                         double quality, String state, String lastUpdated) {
}
