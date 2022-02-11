/*
 * Copyright 2021 EPAM Systems.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.digital.data.platform.restapi.core.service;

import com.epam.digital.data.platform.dso.api.dto.SignRequestDto;
import com.epam.digital.data.platform.dso.api.dto.VerificationRequestDto;
import com.epam.digital.data.platform.dso.api.dto.VerificationResponseDto;
import com.epam.digital.data.platform.dso.client.DigitalSealRestClient;
import com.epam.digital.data.platform.dso.client.exception.BadRequestException;
import com.epam.digital.data.platform.dso.client.exception.InternalServerErrorException;
import com.epam.digital.data.platform.integration.ceph.service.CephService;
import com.epam.digital.data.platform.model.core.kafka.SecurityContext;
import com.epam.digital.data.platform.restapi.core.exception.DigitalSignatureNotFoundException;
import com.epam.digital.data.platform.restapi.core.exception.InvalidSignatureException;
import com.epam.digital.data.platform.restapi.core.exception.KepServiceBadRequestException;
import com.epam.digital.data.platform.restapi.core.exception.KepServiceInternalServerErrorException;
import com.epam.digital.data.platform.restapi.core.utils.Header;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.RuntimeJsonMappingException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DigitalSignatureService {

  private final Logger log = LoggerFactory.getLogger(DigitalSignatureService.class);

  private static final String SIGNATURE = "signature";
  private static final String PREFIX = "datafactory-";
  private final CephService lowcodeCephService;
  private final CephService datafactoryCephService;
  private final String lowcodeBucket;
  private final String datafactoryBucket;
  private final DigitalSealRestClient digitalSealRestClient;
  private final ObjectMapper objectMapper;

  public DigitalSignatureService(
      CephService lowcodeCephService,
      CephService datafactoryCephService,
      @Value("${ceph.bucket}") String lowcodeBucket,
      @Value("${datafactoryceph.bucket}") String datafactoryBucket,
      DigitalSealRestClient digitalSealRestClient,
      ObjectMapper objectMapper) {
    this.lowcodeCephService = lowcodeCephService;
    this.datafactoryCephService = datafactoryCephService;
    this.lowcodeBucket = lowcodeBucket;
    this.datafactoryBucket = datafactoryBucket;
    this.digitalSealRestClient = digitalSealRestClient;
    this.objectMapper = objectMapper;
  }

  public void checkSignature(String data, SecurityContext sc) throws JsonProcessingException {
    String signature = getSignature(sc);
    verify(signature, data);
  }

  private String getSignature(SecurityContext sc) throws JsonProcessingException {
    log.info("Reading Signature from Ceph");
    String responseFromCeph =
        lowcodeCephService
            .getAsString(lowcodeBucket, sc.getDigitalSignatureDerived())
            .orElseThrow(
                () -> new DigitalSignatureNotFoundException("Signature does not exist in ceph bucket"));
    Map<String, Object> cephResponse = objectMapper.readValue(responseFromCeph, Map.class);
    return (String) cephResponse.get(SIGNATURE);
  }

  private void verify(String signature, String data) {
    try {
      log.info("Verifying {}", Header.X_DIGITAL_SIGNATURE_DERIVED.getHeaderName());
      VerificationResponseDto responseDto = digitalSealRestClient.verify(new VerificationRequestDto(signature, data));

      if (!responseDto.isValid()) {
        throw new InvalidSignatureException(responseDto.getError().getMessage());
      }
    } catch (BadRequestException e) {
      throw new KepServiceBadRequestException(e.getMessage());
    } catch (InternalServerErrorException e) {
      throw new KepServiceInternalServerErrorException(e.getMessage());
    }
  }

  public String copySignature(String key) {
    log.info("Copy Signature from lowcode to data ceph bucket");
    String value =
        lowcodeCephService
            .getAsString(lowcodeBucket, key)
            .orElseThrow(
                () -> new DigitalSignatureNotFoundException("Signature does not exist in ceph bucket"));
    datafactoryCephService.put(datafactoryBucket, key, value);
    return value;
  }

  public <I> String sign(I input) {
    var signRequestDto = new SignRequestDto();
    try {
      signRequestDto.setData(objectMapper.writeValueAsString(input));
    } catch (JsonProcessingException e) {
      throw new RuntimeJsonMappingException(e.getMessage());
    }

    log.info("Signing content");
    return digitalSealRestClient.sign(signRequestDto).getSignature();
  }

  public String store(String value) {
    log.info("Storing object to Ceph");

    String key = PREFIX + UUID.randomUUID();
    log.debug("Generated key: {}", key);

    datafactoryCephService.put(datafactoryBucket, key, value);
    return key;
  }
}
