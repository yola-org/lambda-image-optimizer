package com.vestio.imageoptimizer;

import com.vestio.common.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByUserId(UUID userId, Pageable pageable);
    Page<Notification> findByUserIdAndReadAtIsNull(UUID userId, Pageable pageable);
    long countByUserIdAndReadAtIsNull(UUID userId);
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :readAt where n.id = :id and n.userId = :userId and n.readAt is null")
    int markRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("readAt") LocalDateTime readAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :readAt where n.userId = :userId and n.readAt is null and n.createdAt <= :readAt")
    int markAllRead(@Param("userId") UUID userId, @Param("readAt") LocalDateTime readAt);

}
