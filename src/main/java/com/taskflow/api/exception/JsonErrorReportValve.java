package com.taskflow.api.exception;

import com.taskflow.api.util.JsonResponseWriter;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/**
 * Los errores que Tomcat responde antes de llegar a la aplicación (una URL con un escape {@code %} mal formado como
 * {@code /api/v1/workspaces/%zz}, un {@code %00}, un {@code \} o {@code |} sin escapar) salían como la página HTML de
 * Tomcat («HTTP Status 400 – Bad Request»). Este valve los responde en el JSON común de la API, igual que
 * {@link ApiErrorController}. Solo actúa si nadie escribió ya la respuesta: los errores de la aplicación no cambian.
 */
public class JsonErrorReportValve extends ErrorReportValve {

    private final JsonResponseWriter writer;

    public JsonErrorReportValve(JsonResponseWriter writer) {
        this.writer = writer;
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int code = response.getStatus();
        // Como el de Tomcat: nada si no es un error, si ya se escribió algo o si el error ya se informó
        if (code < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        HttpStatus status = HttpStatus.resolve(code);
        try {
            response.resetBuffer();
            writer.writeError(response, code, ApiErrorController.message(
                    status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR));
            response.finishResponse();
        } catch (IOException | IllegalStateException ex) {
            // La conexión ya no admite respuesta: no hay nada más que hacer
        }
    }
}
