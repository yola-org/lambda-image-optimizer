package com.vestio.imageoptimizer;

import com.vestio.common.entity.Notification;
import com.vestio.common.event.payload.NotificationPayload;
import tools.jackson.databind.json.JsonMapper;
import com.vestio.common.entity.enums.NotificationType;
import com.vestio.common.publisher.AppSyncEventsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    @Test
    void savesGenericNotificationAndSendsDirectly() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var publisher = context.getBean(AppSyncEventsPublisher.class);
            var service = context.getBean(NotificationService.class);
            var payload = new MessagePayload("Offer received", null);
            var saved = service.notifyUser(UUID.randomUUID(), NotificationType.OFFER_CREATED, payload);
            assertNotNull(saved.getId());
            assertNull(saved.getListingId());
            assertTrue(saved.getPayload().containsKey("metadata"));
            assertNull(saved.getPayload().get("metadata"));
            assertNotNull(saved.getCreatedAt());
            verify(publisher).dispatchNotificationEvent(argThat(n -> n.getId().equals(saved.getId()) && n.getListingId() == null));
        }
    }

    @Test
    void failedSendRollsBackNotificationSoProcessingCanRetry() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var publisher = context.getBean(AppSyncEventsPublisher.class);
            when(publisher.dispatchNotificationEvent(any()))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("AppSync unavailable")))
                    .thenReturn(CompletableFuture.completedFuture(null));
            var service = context.getBean(NotificationService.class);
            UUID userId = UUID.randomUUID();
            UUID listingId = UUID.randomUUID();
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> service.notifyUserListingPublished(userId, "Shirt", listingId));
            assertEquals(0, context.getBean(NotificationRepository.class).count());
            var saved = service.notifyUserListingPublished(userId, "Shirt", listingId);
            var stored = context.getBean(NotificationRepository.class).findById(saved.getId()).orElseThrow();
            assertEquals(listingId, stored.getListingId());
            assertEquals(NotificationType.LISTING_PUBLISHED, stored.getType());
            assertEquals(Map.of("listingTitle", "Shirt"), stored.getPayload());
            verify(publisher, times(2)).dispatchNotificationEvent(any());
        }
    }

    @Test
    void synchronousSendFailureRollsBackNotification() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var publisher = context.getBean(AppSyncEventsPublisher.class);
            when(publisher.dispatchNotificationEvent(any())).thenThrow(new IllegalStateException("AppSync unavailable"));
            assertThrows(IllegalStateException.class, () -> context.getBean(NotificationService.class)
                    .notifyUserListingPublished(UUID.randomUUID(), "Shirt", UUID.randomUUID()));
            assertEquals(0, context.getBean(NotificationRepository.class).count());
        }
    }

    private record MessagePayload(String message, Object metadata) implements NotificationPayload {}

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:notifications-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.vestio.common.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean AppSyncEventsPublisher publisher() {
            var publisher = mock(AppSyncEventsPublisher.class);
            when(publisher.dispatchNotificationEvent(any())).thenReturn(CompletableFuture.completedFuture(null));
            return publisher;
        }
        @Bean NotificationService notifications(NotificationRepository repository, AppSyncEventsPublisher publisher) {
            return new NotificationService(repository, JsonMapper.builder().build(), publisher);
        }
    }
}
