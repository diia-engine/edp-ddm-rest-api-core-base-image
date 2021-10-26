package com.epam.digital.data.platform.restapi.core.service;

import static com.epam.digital.data.platform.model.core.kafka.Status.JWT_INVALID;
import static com.epam.digital.data.platform.model.core.kafka.Status.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.epam.digital.data.platform.model.core.kafka.Request;
import com.epam.digital.data.platform.model.core.kafka.RequestContext;
import com.epam.digital.data.platform.model.core.kafka.SecurityContext;
import com.epam.digital.data.platform.restapi.core.dto.MockEntity;
import com.epam.digital.data.platform.restapi.core.service.impl.GenericSearchServiceTestImpl;
import com.epam.digital.data.platform.restapi.core.searchhandler.AbstractSearchHandler;
import com.epam.digital.data.platform.restapi.core.util.MockEntityContains;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(classes = GenericSearchServiceTestImpl.class)
class GenericSearchServiceTest {

  @MockBean
  AbstractSearchHandler<MockEntityContains, MockEntity> searchHandler;
  @MockBean
  JwtValidationService jwtValidationService;

  @Autowired
  GenericSearchServiceTestImpl instance;

  @Test
  void shouldSearchInHandler() {
    when(jwtValidationService.isValid(any())).thenReturn(true);
    var c = mockResult();

    given(searchHandler.search(any(Request.class))).willReturn(List.of(c));

    var response = instance.request(mockRequest());

    assertThat(response.getStatus()).isEqualTo(SUCCESS);
    assertThat(response.getPayload()).hasSize(1);
    assertThat(response.getPayload().get(0)).isEqualTo(c);
  }

  @Test
  void shouldReturnInvalidJwtStatus() {
    when(jwtValidationService.isValid(any())).thenReturn(false);

    var response = instance.request(mockRequest());

    assertThat(response.getStatus()).isEqualTo(JWT_INVALID);
  }

  private MockEntityContains mockSc() {
    return new MockEntityContains();
  }

  private MockEntity mockResult() {
    var c = new MockEntity();
    c.setPersonFullName("Some Full Name");
    return c;
  }

  private Request<MockEntityContains> mockRequest() {
    return new Request<>(mockSc(), new RequestContext(), new SecurityContext());
  }
}
