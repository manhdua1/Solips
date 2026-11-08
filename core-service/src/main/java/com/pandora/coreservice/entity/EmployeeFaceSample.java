package com.pandora.coreservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "employee_face_samples")
public class EmployeeFaceSample {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private EmployeeFaceEnrollment enrollment;

    @Column(name = "slot", nullable = false)
    private int slot;

    @Lob
    @Column(name = "embedding", nullable = false, columnDefinition = "BLOB")
    private byte[] embedding;

    @Column(name = "dimension", nullable = false)
    private int dimension = 512;

    @Column(name = "model_id", nullable = false, length = 64)
    private String modelId;

    @Column(name = "model_version", nullable = false, length = 128)
    private String modelVersion;

    @Column(name = "preprocessing_version", nullable = false, length = 64)
    private String preprocessingVersion;

    @Column(name = "det_score")
    private Float detScore;

    @Column(name = "face_width")
    private Integer faceWidth;

    @Column(name = "face_height")
    private Integer faceHeight;

    @Column(name = "face_area_ratio")
    private Float faceAreaRatio;

    @Column(name = "laplacian_var")
    private Float laplacianVar;

    @Column(name = "mean_brightness")
    private Float meanBrightness;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
