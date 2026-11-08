package com.pandora.coreservice.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "employee_face_enrollments")
public class EmployeeFaceEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false, unique = true)
    private UUID employeeId;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    @Column(name = "consented_by_admin_id", nullable = false)
    private UUID consentedByAdminId;

    @Column(name = "consent_version", nullable = false, length = 64)
    private String consentVersion;

    @Column(name = "consent_text", nullable = false, columnDefinition = "TEXT")
    private String consentText;

    @Column(name = "model_id", nullable = false, length = 64)
    private String modelId;

    @Column(name = "model_version", nullable = false, length = 128)
    private String modelVersion;

    @Column(name = "preprocessing_version", nullable = false, length = 64)
    private String preprocessingVersion;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount = 3;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "enrollment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EmployeeFaceSample> samples = new ArrayList<>();
}
