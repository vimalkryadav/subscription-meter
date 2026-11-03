package dev.subscriptionmeter.portal;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** Do not echo or log rejected credential fields in validation errors. */
@RestControllerAdvice(assignableTypes={PortalController.class,ApprovalController.class})
public class PortalErrorHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid() { return Map.of("error","Invalid form fields"); }
}
