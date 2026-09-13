package com.lvfast.streaming.media;

import com.lvfast.streaming.media.job.WorkerAccess;
import com.lvfast.streaming.media.storage.MediaObjectStore;
import com.lvfast.streaming.media.storage.S3MediaObjectStore;
import com.lvfast.streaming.media.storage.StorageProperties;
import com.lvfast.streaming.media.upload.UploadPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({StorageProperties.class, UploadPolicy.class, WorkerAccess.class})
@EnableScheduling
public class MediaConfiguration {

    @Bean
    MediaObjectStore mediaObjectStore(StorageProperties properties) {
        return new S3MediaObjectStore(properties);
    }
}
