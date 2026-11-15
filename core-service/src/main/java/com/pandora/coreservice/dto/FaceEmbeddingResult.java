package com.pandora.coreservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaceEmbeddingResult {

    private List<Float> embedding;
    private byte[] embeddingBytes;
    private int dimension;
    private String modelId;
    private String modelVersion;
    private String preprocessingVersion;
    private FaceQualityDto quality;

    public static byte[] floatsToBytes(List<Float> floats) {
        if (floats == null || floats.isEmpty()) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(floats.size() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (Float f : floats) {
            buffer.putFloat(f != null ? f : 0.0f);
        }
        return buffer.array();
    }

    public static List<Float> bytesToFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = bytes.length / Float.BYTES;
        List<Float> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(buffer.getFloat());
        }
        return list;
    }
}
