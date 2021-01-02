package com.rafasoft.api.handlers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.stream.Collectors;


import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.google.samples.pgp.KeyManagementException;
import com.google.samples.pgp.KeyManager;
import com.google.samples.pgp.PgpEncryptor;
import com.google.samples.pgp.PgpEncryptor.PgpEncryptionException;
import com.google.samples.pgp.PgpKeyManager;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

public class PGPS3FileLambda {
    private static final String MY_PASSPHRASE_LOCATION = "MY_PASSPHRASE_LOCATION";
    private static final String MY_PRIVATE_KEY_LOCATION = "MY_PRIVATE_KEY_LOCATION";
    private static final String PARTNER_PUBLIC_KEY_LOCATION = "PARTNER_PUBLIC_KEY_LOCATION";
    private static final Region REGION = Region.EU_CENTRAL_1;
    private final PgpEncryptor encryptor;
    private final S3Client s3;
    private final String destinationBucketName;

    public PGPS3FileLambda() throws KeyManagementException {
        s3 = S3Client.builder().region(REGION).build();
        SsmClient ssmClient = SsmClient.builder().region(REGION).build();

        String publicKey = getParameterFromSsm(ssmClient, System.getenv(PARTNER_PUBLIC_KEY_LOCATION), false);
        System.out.println(
                "Successfully loaded partners public key from parameter " + "/gpayments/prod/pgpkeys/carrier/public");
        String privateKey = getParameterFromSsm(ssmClient, System.getenv(MY_PRIVATE_KEY_LOCATION), true);
        System.out.println(
                "Successfully loaded our private key from parameter " + "/gpayments/prod/pgpkeys/mykey/private");
        String passphrase = getParameterFromSsm(ssmClient, System.getenv(MY_PASSPHRASE_LOCATION), true);
        System.out.println("Successfully loaded passhrase for our private key from parameter "
                + "/gpayments/prod/pgpkeys/mykey/private/passphrase");

        KeyManager keyManager = PgpKeyManager.getInstance();
        try {
            keyManager.addPublicKeys(new ByteArrayInputStream(publicKey.getBytes()));
            keyManager.addSecretKeys(new ByteArrayInputStream(privateKey.getBytes()), passphrase.toCharArray());
            this.encryptor = new PgpEncryptor(PgpKeyManager.getInstance());
            this.encryptor.setAsciiArmour(true);
        } catch (KeyManagementException ex) {
            System.out.println("Problem while adding PGP keys." + ex.getMessage());
            throw ex;
        }
        this.destinationBucketName = System.getenv("ENCRYPTED_FILES_BUCKET_NAME");
    }

    public PGPS3FileLambda(final PgpEncryptor encryptor, final S3Client s3, final String destinationBucketName) {
        this.encryptor = encryptor;
        this.s3 = s3;
        this.destinationBucketName = destinationBucketName;
    }

    public void handler(S3Event event) throws IOException {
        // S3event comes as an array of events. It always has just one element, but
        // better to be defensive and process it as a stream.
        event.getRecords().stream()
        .map(this::getFileContentFromS3)
        .map(this::encryptFileContent)
        .map(this::storeFileInS3)
        .collect(Collectors.toList());
    }

    FileNameWithContent getFileContentFromS3(S3EventNotification.S3EventNotificationRecord record) {
        String bucketName = record.getS3().getBucket().getName();
        String fileName = record.getS3().getObject().getKey();
        String unencryptedFileContent = readContentFromS3(bucketName, fileName);
        System.out.println("BucketName is " + bucketName + ", FileName is " + fileName
                + "Unencrypted file content is : " + unencryptedFileContent);

        return new FileNameWithContent(fileName, unencryptedFileContent);
    }

    // This method should go in some kind of commons module if we need to read in
    // some other lambda.
    private String readContentFromS3(String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
        ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(getObjectRequest);
        return bytes.asUtf8String();
    }

    FileNameWithContent encryptFileContent(final FileNameWithContent fileContent) {
        try {
            String encryptedFileContent = encryptor.encrypt(fileContent.content);
            System.out.println("Encrypted body is: " + encryptedFileContent);
            return new FileNameWithContent(fileContent.fileName, encryptedFileContent);
        } catch (PgpEncryptionException pgpEx) {
            throw new RuntimeException(pgpEx);
        }
    }

    /**
     * Stores the encrypted file in the bucket that holds the encrypted files with
     * the pgp suffix.
     * @param fileNameContent
     * @return true if file was correctly saved, false otherwise.
     */
    boolean storeFileInS3(final FileNameWithContent fileNameContent) {
        System.out.println("We will write file(s) in bucket with name " + destinationBucketName);
        PutObjectRequest objectRequest = PutObjectRequest.builder().bucket(destinationBucketName)
                .key(fileNameContent.fileName + ".pgp").build();
        try {
            s3.putObject(objectRequest, RequestBody.fromString(fileNameContent.content));
            return true;
        } catch (AwsServiceException s3Exception) {
            System.err.println("Problem while saving file " + fileNameContent.fileName + ". Exception: " + s3Exception);
            return false;
        }
    }

    class FileNameWithContent {
        String fileName;
        String content;

        public FileNameWithContent(final String fileName, final String content) {
            this.fileName = fileName;
            this.content = content;
        }
    }

    // This method is needed by the constructor.
    private String getParameterFromSsm(SsmClient ssmClient, String parameterLocation, boolean decryptContents) {
        GetParameterRequest parameterRequest = GetParameterRequest.builder().name(parameterLocation)
                .withDecryption(decryptContents).build();

        GetParameterResponse parameterResponse = ssmClient.getParameter(parameterRequest);
        return parameterResponse.parameter().value();
    }

}