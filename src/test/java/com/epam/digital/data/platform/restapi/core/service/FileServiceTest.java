package com.epam.digital.data.platform.restapi.core.service;

import static com.epam.digital.data.platform.restapi.core.dto.MockEntityFile.FILE_FIELD_NUM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.epam.digital.data.platform.integration.ceph.dto.CephObject;
import com.epam.digital.data.platform.integration.ceph.exception.CephCommuncationException;
import com.epam.digital.data.platform.integration.ceph.service.CephService;
import com.epam.digital.data.platform.model.core.kafka.File;
import com.epam.digital.data.platform.restapi.core.dto.MockEntityFile;
import com.epam.digital.data.platform.restapi.core.exception.ChecksumInconsistencyException;
import com.epam.digital.data.platform.restapi.core.utils.ResponseCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

  static final String LOWCODE_BUCKET_NAME = "lowcode_bucket";
  static final String DATA_BUCKET_NAME = "data_bucket";

  static final String FILE_ID = "id";
  static final String INSTANCE_ID = "instanceId";
  static final String COMPOSITE_FILE_ID = "process/" + INSTANCE_ID + "/" + FILE_ID;

  static final byte[] FILE_CONTENT = "content".getBytes();
  static final String HASH_OF_CONTENT_STRING =
      "ed7002b439e9ac845f22357d822bac1444730fbdb6016d3ec9432297b9ec9f73";
  static final Map<String, String> FILE_METADATA = Map.of("type", "jpg");

  @Mock
  CephService lowcodeCephService;
  @Mock
  CephService datafactoryCephService;

  FileService instance;

  @BeforeEach
  void beforeEach() {
    instance =
        new FileService(
            true,
            LOWCODE_BUCKET_NAME,
            DATA_BUCKET_NAME,
            lowcodeCephService,
            datafactoryCephService);
  }

  private File mockFile(String compositeFileId) {
    var file = new File();
    file.setId(compositeFileId);
    file.setChecksum(HASH_OF_CONTENT_STRING);
    return file;
  }

  @Nested
  class GetFileProperties {

    @Test
    void skipNulls() {
      assertThat(instance.getFileProperties(new MockEntityFile())).isEmpty();
    }

    @Test
    void findPropertiesWithFileType() {
      MockEntityFile e = mockEntityFile();

      var fileProperties = instance.getFileProperties(e);

      assertThat(fileProperties).hasSize(FILE_FIELD_NUM);
    }

    @Test
    void dealWithCollections() {
      var list = List.of(mockEntityFile(), mockEntityFile());

      var fileProperties = instance.getFileProperties(list);

      assertThat(fileProperties).hasSize(list.size() * FILE_FIELD_NUM);
    }

    private MockEntityFile mockEntityFile() {
      var e = new MockEntityFile();
      e.setScanCopy(new File());
      e.setAnotherScanCopy(new File());
      return e;
    }
  }

  @Nested
  class Store {

    @Test
    void copyContentFromLowcodeBucketToDatafactoryBucket() {
      when(lowcodeCephService.getObject(LOWCODE_BUCKET_NAME, COMPOSITE_FILE_ID))
          .thenReturn(Optional.of(new CephObject(FILE_CONTENT, FILE_METADATA)));

      File file = mockFile(FILE_ID);

      instance.store(INSTANCE_ID, file);

      verify(datafactoryCephService)
          .putObject(DATA_BUCKET_NAME, FILE_ID, new CephObject(FILE_CONTENT, FILE_METADATA));
    }

    @Test
    void expectTrueWhenSuccess() {
      when(lowcodeCephService.getObject(LOWCODE_BUCKET_NAME, COMPOSITE_FILE_ID))
          .thenReturn(Optional.of(new CephObject(FILE_CONTENT, FILE_METADATA)));

      File file = mockFile(FILE_ID);

      var success = instance.store(INSTANCE_ID, file);

      assertThat(success).isTrue();
    }

    @Test
    void expectFalseIfFileNotExistsInCeph() {
      when(lowcodeCephService.getObject(LOWCODE_BUCKET_NAME, COMPOSITE_FILE_ID))
          .thenReturn(Optional.empty());

      File file = mockFile(FILE_ID);

      var success = instance.store(INSTANCE_ID, file);

      assertThat(success).isFalse();
    }

    @Test
    void expectExceptionIfCephFailed() {
      var amazonS3Exception = new AmazonS3Exception("");
      amazonS3Exception.setErrorCode(ResponseCode.CLIENT_ERROR);
      amazonS3Exception.setStatusCode(INTERNAL_SERVER_ERROR.value());
      when(lowcodeCephService.getObject(LOWCODE_BUCKET_NAME, COMPOSITE_FILE_ID))
          .thenThrow(new CephCommuncationException("", amazonS3Exception));

      File file = mockFile(FILE_ID);

      assertThrows(RuntimeException.class, () -> instance.store(INSTANCE_ID, file));
    }

    @Test
    void expectExceptionIfStoredFileWasChanged() {
      when(lowcodeCephService.getObject(LOWCODE_BUCKET_NAME, COMPOSITE_FILE_ID))
          .thenReturn(Optional.of(new CephObject("wrong content".getBytes(), FILE_METADATA)));

      var file = mockFile(FILE_ID);

      assertThrows(ChecksumInconsistencyException.class, () -> instance.store(INSTANCE_ID, file));
    }

    @Test
    void expectSkipCephFlowIfFileProcessingDisabled() {
      instance =
          new FileService(
              false,
              LOWCODE_BUCKET_NAME,
              DATA_BUCKET_NAME,
              lowcodeCephService,
              datafactoryCephService);

      File file = mockFile(FILE_ID);

      instance.store(INSTANCE_ID, file);

      verifyNoInteractions(lowcodeCephService, datafactoryCephService);
    }
  }

  @Nested
  class Retrieve {

    @Test
    void copyContentFromDatafactoryBucketToLowcodeBucket() {
      when(datafactoryCephService.getObject(DATA_BUCKET_NAME, FILE_ID))
          .thenReturn(Optional.of(new CephObject(FILE_CONTENT, FILE_METADATA)));

      File file = mockFile(FILE_ID);

      instance.retrieve(INSTANCE_ID, file);

      verify(lowcodeCephService)
          .putObject(LOWCODE_BUCKET_NAME, COMPOSITE_FILE_ID, new CephObject(FILE_CONTENT, FILE_METADATA));
    }

    @Test
    void expectTrueWhenSuccess() {
      when(datafactoryCephService.getObject(DATA_BUCKET_NAME, FILE_ID))
          .thenReturn(Optional.of(new CephObject(FILE_CONTENT, FILE_METADATA)));

      File file = mockFile(FILE_ID);

      var success = instance.retrieve(INSTANCE_ID, file);

      assertThat(success).isTrue();
    }

    @Test
    void expectFalseIfFileNotExistsInCeph() {
      when(datafactoryCephService.getObject(DATA_BUCKET_NAME, FILE_ID))
          .thenReturn(Optional.empty());

      File file = mockFile(FILE_ID);

      var success = instance.retrieve(INSTANCE_ID, file);

      assertThat(success).isFalse();
    }

    @Test
    void expectExceptionIfCephFailed() {
      var amazonS3Exception = new AmazonS3Exception("");
      amazonS3Exception.setErrorCode(ResponseCode.CLIENT_ERROR);
      amazonS3Exception.setStatusCode(INTERNAL_SERVER_ERROR.value());
      when(datafactoryCephService.getObject(DATA_BUCKET_NAME, FILE_ID))
          .thenThrow(new CephCommuncationException("", amazonS3Exception));

      File file = mockFile(FILE_ID);

      assertThrows(RuntimeException.class, () -> instance.retrieve(INSTANCE_ID, file));
    }

    @Test
    void expectExceptionIfRetrievedFileWasChanged() {
      when(datafactoryCephService.getObject(DATA_BUCKET_NAME, FILE_ID))
          .thenReturn(Optional.of(new CephObject("wrong content".getBytes(), FILE_METADATA)));

      var file = mockFile(FILE_ID);

      assertThrows(ChecksumInconsistencyException.class, () -> instance.retrieve(INSTANCE_ID, file));
    }

    @Test
    void expectSkipCephFlowIfFileProcessingDisabled() {
      instance =
          new FileService(
              false,
              LOWCODE_BUCKET_NAME,
              DATA_BUCKET_NAME,
              lowcodeCephService,
              datafactoryCephService);

      File file = mockFile(FILE_ID);

      instance.retrieve(INSTANCE_ID, file);

      verifyNoInteractions(lowcodeCephService, datafactoryCephService);
    }
  }
}
