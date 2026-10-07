package space.raychi.wellspring.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.service.AdminAccountService;
import space.raychi.wellspring.service.AdminPrincipal;

class WritingStreamAuthorizationTest {
  @Test
  void openStreamStopsReadingAfterPasswordChangeOrLogout() throws Exception {
    var writing = mock(WritingService.class);
    var streams = mock(WritingStreams.class);
    var accounts = mock(AdminAccountService.class);
    var owner = new AdminPrincipal("owner", "unused", 1);
    var auth = UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities());
    var request = new MockHttpServletRequest();
    var session = request.getSession();
    var state = new ObjectMapper().readTree("{\"status\":\"running\"}");
    when(writing.get("task", "owner")).thenReturn(state);
    when(accounts.isCurrent(owner)).thenReturn(true);
    when(streams.open(any(), eq(state))).thenReturn(new SseEmitter());
    var controller = new AgentController(mock(AgentClient.class), writing, streams, accounts);
    controller.events("task", auth, request, new MockHttpServletResponse());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Supplier<JsonNode>> reader = ArgumentCaptor.forClass(Supplier.class);
    verify(streams).open(reader.capture(), eq(state));
    assertThat(reader.getValue().get()).isEqualTo(state);
    clearInvocations(writing);
    when(accounts.isCurrent(owner)).thenReturn(false);
    assertThatThrownBy(() -> reader.getValue().get()).isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException)e).status().value()).isEqualTo(401));
    verifyNoInteractions(writing);
    when(accounts.isCurrent(owner)).thenReturn(true);
    session.invalidate();
    assertThatThrownBy(() -> reader.getValue().get()).isInstanceOf(ApiException.class);
    verifyNoInteractions(writing);
  }

  @Test
  void taskOwnershipIsCheckedBeforeStreamOpens() {
    var writing = mock(WritingService.class);
    var streams = mock(WritingStreams.class);
    var owner = new AdminPrincipal("owner", "unused", 1);
    var auth = UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities());
    when(writing.get("foreign", "owner")).thenThrow(AgentClient.error(404, "NOT_FOUND"));
    var controller = new AgentController(mock(AgentClient.class), writing, streams, mock(AdminAccountService.class));
    assertThatThrownBy(() -> controller.events("foreign", auth, new MockHttpServletRequest(), new MockHttpServletResponse()))
        .isInstanceOf(ApiException.class);
    verifyNoInteractions(streams);
  }
}
