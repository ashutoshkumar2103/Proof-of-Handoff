package com.handoffly.support.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SupportStaffRepository extends JpaRepository<SupportStaff, Long>, JpaSpecificationExecutor<SupportStaff> {

    /** Row-locks the staff member for the rest of the transaction, so two admins cannot change them at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupportStaff s where s.staffCode = :staffCode")
    Optional<SupportStaff> findByStaffCodeForUpdate(@Param("staffCode") String staffCode);

    Optional<SupportStaff> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<SupportStaff> findByIdAndActiveTrue(Long id);
}
