package app.gitwatch.api;

import app.gitwatch.api.ApiEnvelopes.ErrorBody;
import app.gitwatch.api.ApiEnvelopes.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class ApiResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiResponses() {
    }

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(), new ErrorResponse(new ErrorBody(code, message)));
    }
}
