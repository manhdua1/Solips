package com.pandora.coreservice.repo;

import com.pandora.coreservice.dto.FaceCandidateSampleDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryContractTest {

    @Test
    @DisplayName("EmployeeRepository.clearDepartmentByDepartmentId has @Modifying with flushAutomatically and clearAutomatically set to true")
    void employeeRepoClearDepartmentModifyingContract() throws NoSuchMethodException {
        Method method = EmployeeRepository.class.getMethod("clearDepartmentByDepartmentId", UUID.class);
        Modifying modifying = method.getAnnotation(Modifying.class);

        assertThat(modifying)
                .as("clearDepartmentByDepartmentId must be annotated with @Modifying")
                .isNotNull();
        assertThat(modifying.flushAutomatically())
                .as("flushAutomatically must be true to flush changes before bulk update")
                .isTrue();
        assertThat(modifying.clearAutomatically())
                .as("clearAutomatically must be true to invalidate persistence context after bulk update")
                .isTrue();
    }

    @Test
    @DisplayName("DepartmentRepository.clearManagerByManagerId has @Modifying with flushAutomatically and clearAutomatically set to true")
    void departmentRepoClearManagerModifyingContract() throws NoSuchMethodException {
        Method method = DepartmentRepository.class.getMethod("clearManagerByManagerId", UUID.class);
        Modifying modifying = method.getAnnotation(Modifying.class);

        assertThat(modifying)
                .as("clearManagerByManagerId must be annotated with @Modifying")
                .isNotNull();
        assertThat(modifying.flushAutomatically())
                .as("flushAutomatically must be true to flush changes before bulk update")
                .isTrue();
        assertThat(modifying.clearAutomatically())
                .as("clearAutomatically must be true to invalidate persistence context after bulk update")
                .isTrue();
    }

    @Test
    @DisplayName("EmployeeFaceSampleRepository.findActiveCandidateSamples has valid scalar projection contract")
    void employeeFaceSampleRepoCandidateQueryContract() throws NoSuchMethodException {
        Method method = EmployeeFaceSampleRepository.class.getMethod("findActiveCandidateSamples");
        Query query = method.getAnnotation(Query.class);

        assertThat(query).as("findActiveCandidateSamples must be annotated with @Query").isNotNull();
        assertThat(query.value())
                .contains("new com.pandora.coreservice.dto.FaceCandidateSampleDto(")
                .contains("JOIN s.enrollment en")
                .contains("JOIN Employee e ON en.employeeId = e.id")
                .contains("e.status = com.pandora.coreservice.entity.EmployeeStatus.ACTIVE")
                .contains("e.deletedAt IS NULL");

        assertThat(FaceCandidateSampleDto.class.getConstructors()).anySatisfy(c -> {
            assertThat(c.getParameterTypes()).containsExactly(
                    UUID.class,
                    byte[].class,
                    int.class,
                    String.class,
                    String.class,
                    String.class
            );
        });
    }
}
