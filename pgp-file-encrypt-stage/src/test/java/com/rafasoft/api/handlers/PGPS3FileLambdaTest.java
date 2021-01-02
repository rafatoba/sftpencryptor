package com.rafasoft.api.handlers;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3BucketEntity;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3Entity;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3ObjectEntity;
import com.google.samples.pgp.PgpEncryptor;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

public class PGPS3FileLambdaTest {
    private static final String TEXT_TO_ENCRYPT = "testString";
    private static final String DESTINATION_BUCKET = "destinationBucket";
    private static final String OBJECT_KEY = "objectKey";
    private static final String BUCKET_NAME = "bucketName";
    @Mock
    private PgpEncryptor pgpEncryptor;
    @Mock
    private S3Client s3Client;
    @Captor
    private ArgumentCaptor<String> textToEncryptCaptor;
    
    private PGPS3FileLambda pgpS3FileLambda;
    
    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        pgpS3FileLambda = new PGPS3FileLambda(pgpEncryptor, s3Client, DESTINATION_BUCKET);
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void testHandleSuccess() throws Exception {
        S3Event event = prepareS3Event();
        ResponseBytes<GetObjectResponse> response = (ResponseBytes<GetObjectResponse>)Mockito.mock(ResponseBytes.class);
        when(response.asUtf8String()).thenReturn(TEXT_TO_ENCRYPT);
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(response);
        when(pgpEncryptor.encrypt(ArgumentMatchers.anyString())).thenReturn("encryptedTestString");

        
        pgpS3FileLambda.handler(event);
        
        verify(pgpEncryptor).encrypt(textToEncryptCaptor.capture());
        Assertions.assertEquals(TEXT_TO_ENCRYPT, textToEncryptCaptor.getValue());
    }



    private S3Event prepareS3Event() {
        S3BucketEntity bucketEntity = Mockito.mock(S3BucketEntity.class);
        when(bucketEntity.getName()).thenReturn(BUCKET_NAME);
        S3ObjectEntity objectEntity = Mockito.mock(S3ObjectEntity.class);
        when(objectEntity.getKey()).thenReturn(OBJECT_KEY);
        S3Entity s3Entity = Mockito.mock(S3Entity.class);
        when(s3Entity.getBucket()).thenReturn(bucketEntity);
        when(s3Entity.getObject()).thenReturn(objectEntity);
        S3EventNotificationRecord notificationRecord = Mockito.mock(S3EventNotificationRecord.class);
        when(notificationRecord.getS3()).thenReturn(s3Entity);
        List<S3EventNotificationRecord> listOfEventNotifications = new ArrayList<>();
        listOfEventNotifications.add(notificationRecord);
        S3Event event = Mockito.mock(S3Event.class);
        when(event.getRecords()).thenReturn(listOfEventNotifications);
        return event;
    }


    
}
