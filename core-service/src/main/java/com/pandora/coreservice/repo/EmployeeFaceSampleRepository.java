package com.pandora.coreservice.repo;

import com.pandora.coreservice.entity.EmployeeFaceSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmployeeFaceSampleRepository extends JpaRepository<EmployeeFaceSample, UUID> {

    List<EmployeeFaceSample> findByEnrollmentIdOrderBySlotAsc(UUID enrollmentId);

    void deleteByEnrollmentId(UUID enrollmentId);
}
