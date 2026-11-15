package com.pandora.coreservice.repo;

import com.pandora.coreservice.entity.EmployeeFaceEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeFaceEnrollmentRepository extends JpaRepository<EmployeeFaceEnrollment, UUID> {

    Optional<EmployeeFaceEnrollment> findByEmployeeId(UUID employeeId);

    List<EmployeeFaceEnrollment> findByEmployeeIdIn(Collection<UUID> employeeIds);

    void deleteByEmployeeId(UUID employeeId);

    boolean existsByEmployeeId(UUID employeeId);
}
