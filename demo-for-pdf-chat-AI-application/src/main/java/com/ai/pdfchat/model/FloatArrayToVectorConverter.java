package com.ai.pdfchat.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.stream.Collectors;

@Converter(autoApply = false)
public class FloatArrayToVectorConverter implements AttributeConverter<float[], String> {
    @Override
    public String convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) return null;

        // manually build string because float[] cannot be streamed directly
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < attribute.length; i++) {
            sb.append(attribute[i]);
            if (i < attribute.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public float[] convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;

        // Correct regex: escape backslash + brackets
        String s = dbData.replaceAll("[\\[\\]]", "").trim();

        if (s.isEmpty()) return new float[0];

        String[] parts = s.split(",");
        float[] arr = new float[parts.length];

        for (int i = 0; i < parts.length; i++) {
            arr[i] = Float.parseFloat(parts[i].trim());
        }

        return arr;
    }

}
