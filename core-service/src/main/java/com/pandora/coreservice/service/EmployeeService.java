package com.pandora.coreservice.service;

import com.pandora.coreservice.dto.EmployeeCreateRequest;
import com.pandora.coreservice.dto.EmployeeResponse;
import com.pandora.coreservice.dto.EmployeeUpdateRequest;
import com.pandora.coreservice.dto.PageResponse;
import com.pandora.coreservice.entity.Department;
import com.pandora.coreservice.entity.Employee;
import com.pandora.coreservice.entity.EmployeeFaceEnrollment;
import com.pandora.coreservice.entity.EmployeeStatus;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.repo.DepartmentRepository;
import com.pandora.coreservice.repo.EmployeeFaceEnrollmentRepository;
import com.pandora.coreservice.repo.EmployeeFaceSampleRepository;
import com.pandora.coreservice.repo.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeFaceEnrollmentRepository employeeFaceEnrollmentRepository;
    private final EmployeeFaceSampleRepository employeeFaceSampleRepository;

    @Transactional
    public EmployeeResponse create(EmployeeCreateRequest request) {
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        String code = request.getCode() != null ? request.getCode().trim() : null;
        String fullName = request.getFullName() != null ? request.getFullName().trim() : null;
        String email = request.getEmail() != null ? request.getEmail().trim() : null;

        if (code == null || code.isEmpty() || code.length() > 255 ||
                fullName == null || fullName.isEmpty() || fullName.length() > 255 ||
                email == null || email.isEmpty() || email.length() > 255) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        if (employeeRepository.existsByCode(code)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }
        if (employeeRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }

        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findActiveByIdForReference(request.getDepartmentId())
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        }

        EmployeeStatus status = request.getStatus() != null ? request.getStatus() : EmployeeStatus.ACTIVE;

        Employee employee = new Employee();
        employee.setCode(code);
        employee.setFullName(fullName);
        employee.setEmail(email);
        employee.setDepartment(department);
        employee.setStatus(status);
        employee.setDeletedAt(null);

        Employee saved = employeeRepository.save(employee);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployeeResponse> getPaginated(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<Employee> empPage = employeeRepository.findByDeletedAtIsNull(pageable);
        List<Employee> employees = empPage.getContent();
        List<UUID> employeeIds = employees.stream().map(Employee::getId).toList();
        Map<UUID, EmployeeFaceEnrollment> enrollmentMap = findEnrollmentMap(employeeIds);

        List<EmployeeResponse> content = employees.stream()
                .map(emp -> toResponse(emp, enrollmentMap.get(emp.getId())))
                .toList();

        return PageResponse.<EmployeeResponse>builder()
                .content(content)
                .page(empPage.getNumber())
                .size(empPage.getSize())
                .totalElements(empPage.getTotalElements())
                .totalPages(empPage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse getById(UUID id) {
        if (id == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Employee employee = employeeRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        Map<UUID, EmployeeFaceEnrollment> enrollmentMap = findEnrollmentMap(List.of(id));
        return toResponse(employee, enrollmentMap.get(id));
    }

    @Transactional
    public EmployeeResponse update(UUID id, EmployeeUpdateRequest request) {
        if (id == null || request == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Employee employee = employeeRepository.findActiveByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        String code = request.getCode() != null ? request.getCode().trim() : null;
        String fullName = request.getFullName() != null ? request.getFullName().trim() : null;
        String email = request.getEmail() != null ? request.getEmail().trim() : null;

        if (code == null || code.isEmpty() || code.length() > 255 ||
                fullName == null || fullName.isEmpty() || fullName.length() > 255 ||
                email == null || email.isEmpty() || email.length() > 255) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        if (request.getStatus() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        if (employeeRepository.existsByCodeAndIdNot(code, id)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }
        if (employeeRepository.existsByEmailAndIdNot(email, id)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }

        if (request.getDepartmentId() != null) {
            Department department = departmentRepository.findActiveByIdForReference(request.getDepartmentId())
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
            employee.setDepartment(department);
        } else {
            employee.setDepartment(null);
        }

        employee.setCode(code);
        employee.setFullName(fullName);
        employee.setEmail(email);
        employee.setStatus(request.getStatus());

        Employee saved = employeeRepository.save(employee);
        Map<UUID, EmployeeFaceEnrollment> enrollmentMap = findEnrollmentMap(List.of(id));
        return toResponse(saved, enrollmentMap.get(id));
    }

    @Transactional
    public void delete(UUID id) {
        if (id == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Employee employee = employeeRepository.findActiveByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        Optional<EmployeeFaceEnrollment> enrollmentOpt = employeeFaceEnrollmentRepository.findByEmployeeId(id);
        if (enrollmentOpt.isPresent()) {
            EmployeeFaceEnrollment enrollment = enrollmentOpt.get();
            employeeFaceSampleRepository.deleteByEnrollmentId(enrollment.getId());
            employeeFaceEnrollmentRepository.delete(enrollment);
        }

        employee.setFaceRevision(employee.getFaceRevision() + 1);
        employee.setDeletedAt(Instant.now());
        employeeRepository.save(employee);

        departmentRepository.clearManagerByManagerId(id);
    }

    public EmployeeResponse toResponse(Employee employee) {
        return toResponse(employee, null);
    }

    public EmployeeResponse toResponse(Employee employee, EmployeeFaceEnrollment enrollment) {
        return EmployeeResponse.builder()
                .id(employee.getId())
                .code(employee.getCode())
                .fullName(employee.getFullName())
                .email(employee.getEmail())
                .departmentId(employee.getDepartment() != null ? employee.getDepartment().getId() : null)
                .status(employee.getStatus())
                .faceRegistered(enrollment != null)
                .faceSampleCount(enrollment != null ? enrollment.getSampleCount() : 0)
                .build();
    }

    private Map<UUID, EmployeeFaceEnrollment> findEnrollmentMap(Collection<UUID> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return employeeFaceEnrollmentRepository.findByEmployeeIdIn(employeeIds).stream()
                .collect(Collectors.toMap(
                        EmployeeFaceEnrollment::getEmployeeId,
                        Function.identity(),
                        (existing, replacing) -> existing
                ));
    }
}
