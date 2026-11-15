package com.pandora.coreservice.repo;

import com.pandora.coreservice.dto.FaceCandidateSampleDto;
import com.pandora.coreservice.entity.EmployeeFaceSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeFaceSampleRepository extends JpaRepository<EmployeeFaceSample, UUID> {

    List<EmployeeFaceSample> findByEnrollmentIdOrderBySlotAsc(UUID enrollmentId);

    void deleteByEnrollmentId(UUID enrollmentId);

    @Query("""
        SELECT new com.pandora.coreservice.dto.FaceCandidateSampleDto(
            en.employeeId,
            s.embedding,
            s.dimension,
            s.modelId,
            s.modelVersion,
            s.preprocessingVersion
        )
        FROM EmployeeFaceSample s
        JOIN s.enrollment en
        JOIN Employee e ON en.employeeId = e.id
        WHERE e.status = com.pandora.coreservice.entity.EmployeeStatus.ACTIVE
          AND e.deletedAt IS NULL
    """)
    List<FaceCandidateSampleDto> findActiveCandidateSamples();
}
