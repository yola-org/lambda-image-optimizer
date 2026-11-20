package com.vestio.imageoptimizer;

import com.vestio.common.entity.Notification;
import com.vestio.common.entity.enums.NotificationType;
import com.vestio.common.publisher.AppSyncEventsPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
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
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    @Test
    void savesGenericNotificationAndPublishesOnlyAfterCommit() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var publisher = context.getBean(AppSyncEventsPublisher.class);
            var service = context.getBean(NotificationService.class);
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("message", "Offer received");
            payload.put("metadata", null);
            var saved = new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
                var notification = service.notifyUser(UUID.randomUUID(), NotificationType.OFFER_CREATED, payload);
                verifyNoInteractions(publisher);
                return notification;
            });
            assertNotNull(saved.getId());
            assertNull(saved.getListingId());
            assertTrue(saved.getPayload().containsKey("metadata"));
            assertNull(saved.getPayload().get("metadata"));
            assertNotNull(saved.getCreatedAt());
            verify(publisher).dispatchNotificationEvent(argThat(n -> n.getId().equals(saved.getId()) && n.getListingId() == null));
        }
    }

    @Test
    void rollbackDoesNotSendNotification() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                context.getBean(NotificationService.class).notifyUserListingPublished(UUID.randomUUID(), "Shirt", UUID.randomUUID());
                status.setRollbackOnly();
            });
            verifyNoInteractions(context.getBean(AppSyncEventsPublisher.class));
            assertEquals(0, context.getBean(NotificationRepository.class).count());
        }
    }

    @Test
    void appSyncFailureKeepsCommittedInboxNotification() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var publisher = context.getBean(AppSyncEventsPublisher.class);
            when(publisher.dispatchNotificationEvent(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("AppSync unavailable")));
            UUID listingId = UUID.randomUUID();
            var saved = context.getBean(NotificationService.class).notifyUserListingPublished(UUID.randomUUID(), "Shirt", listingId);
            var stored = context.getBean(NotificationRepository.class).findById(saved.getId()).orElseThrow();
            assertEquals(listingId, stored.getListingId());
            assertEquals(NotificationType.LISTING_PUBLISHED, stored.getType());
            assertEquals(Map.of("listingTitle", "Shirt"), stored.getPayload());
            verify(publisher).dispatchNotificationEvent(any());
        }
    }

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
        @Bean NotificationService notifications(NotificationRepository repository, ApplicationEventPublisher events) {
            return new NotificationService(repository, events);
        }
        @Bean NotificationDispatcher dispatcher(AppSyncEventsPublisher publisher) { return new NotificationDispatcher(publisher); }
    }
}
