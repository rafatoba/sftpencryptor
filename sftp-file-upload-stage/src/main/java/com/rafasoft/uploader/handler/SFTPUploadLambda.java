package com.rafasoft.uploader.handler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Collectors;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

public class SFTPUploadLambda {
    private static final Region REGION = Region.EU_CENTRAL_1;
    private final S3Client s3;
    private final String sftpHost;
    private final String sftpPort;
    private final String sftpUsername;
    private final String sftpPassword;

    public SFTPUploadLambda() {
        s3 = S3Client.builder().region(REGION).build();
        SsmClient ssmClient = SsmClient.builder().region(REGION).build();
        sftpHost = getParameterFromSsm(ssmClient, System.getenv("SFTP_HOST"), false);
        sftpPort = getParameterFromSsm(ssmClient, System.getenv("SFTP_PORT"), false);
        sftpUsername = getParameterFromSsm(ssmClient, System.getenv("SFTP_USERNAME"), false);
        sftpPassword = getParameterFromSsm(ssmClient, System.getenv("SFTP_PASSWORD"), true);
    }

    public void handler(S3Event event) throws IOException {
        System.out.println("Treating event " + event.toString());

        // S3event comes as an array of events. It always has just one element, but
        // better to be defensive and process it as a stream.
        event.getRecords().stream().map(this::getFileContentFromS3).map(this::storeFileInSFTP)
                .collect(Collectors.toList());

        System.out.println("Finished request processing.");
    }

    FileNameWithContent getFileContentFromS3(S3EventNotification.S3EventNotificationRecord record) {
        String bucketName = record.getS3().getBucket().getName();
        String fileName = record.getS3().getObject().getKey();
        String unencryptedFileContent = readContentFromS3(bucketName, fileName);
        System.out.println("BucketName is " + bucketName + ", FileName is " + fileName
                + "Unencrypted file content is : " + unencryptedFileContent);

        return new FileNameWithContent(fileName, unencryptedFileContent);
    }

    boolean storeFileInSFTP(FileNameWithContent fileNameContent) {
        try {
            System.out.println("Starting with SFTP upload.");
            SSHClient client = new SSHClient();
            client.addHostKeyVerifier(new PromiscuousVerifier());
            client.connect(sftpHost, Integer.parseInt(sftpPort));
            client.authPassword(sftpUsername, sftpPassword);
            SFTPClient sftpClient = client.newSFTPClient();
            InMemorySourceFile inmemorySourceFile = getInMemoryFile(fileNameContent);
            sftpClient.put(inmemorySourceFile, "/upload/" + fileNameContent.fileName);
            client.close();
            System.out.println("SFTP uploaded the file " + fileNameContent.fileName);
            return true;
        } catch (Exception ex) {
            System.err.println("Problem with SFTP: " + ex);
            return false;
        }

    }

    private InMemorySourceFile getInMemoryFile(FileNameWithContent fileNameContent) {
        final byte[] bytes = fileNameContent.content.getBytes();
        final String fileName = fileNameContent.fileName;

        InMemorySourceFile inmemorySourceFile = new InMemorySourceFile() {

            @Override
            public String getName() {
                return fileName;
            }

            @Override
            public long getLength() {
                return bytes.length;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(bytes);
            }
        };
        return inmemorySourceFile;
    }

    // This method should go in some kind of commons module if we need to read in
    // some other lambda.
    private String readContentFromS3(String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
        ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(getObjectRequest);
        return bytes.asUtf8String();
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
