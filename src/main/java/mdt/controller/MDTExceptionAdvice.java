package mdt.controller;

import java.nio.file.AccessDeniedException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import mdt.client.Fa3stMessage;
import mdt.model.InvalidResourceStatusException;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;

import jakarta.servlet.http.HttpServletRequest;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestControllerAdvice
public class MDTExceptionAdvice {
	@ExceptionHandler({IllegalArgumentException.class})
	public ResponseEntity<Fa3stMessage> exceptionHandler(HttpServletRequest request,
																IllegalArgumentException e) {
		return ResponseEntity
				.status(HttpStatus.BAD_REQUEST)
				.body(Fa3stMessage.from(e));
	}
	
	@ExceptionHandler({ResourceNotFoundException.class})
	public ResponseEntity<Fa3stMessage> exceptionHandler(HttpServletRequest request,
																ResourceNotFoundException e) {
		return ResponseEntity
				.status(HttpStatus.NOT_FOUND)
				.body(Fa3stMessage.from(e));
	}
	
	@ExceptionHandler({ResourceAlreadyExistsException.class})
	public ResponseEntity<Fa3stMessage> exceptionHandler(HttpServletRequest request,
																ResourceAlreadyExistsException e) {
		return ResponseEntity
				.status(HttpStatus.BAD_REQUEST)
				.body(Fa3stMessage.from(e));
	}
	
	@ExceptionHandler({InvalidResourceStatusException.class})
	public ResponseEntity<Fa3stMessage> exceptionHandler(HttpServletRequest request,
																	InvalidResourceStatusException e) {
		return ResponseEntity
				.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Fa3stMessage.from(e));
	}

	@ExceptionHandler({UnsupportedOperationException.class})
	public ResponseEntity<Fa3stMessage> exceptionHandler(HttpServletRequest request,
																UnsupportedOperationException e) {
		return ResponseEntity
				.status(HttpStatus.NOT_IMPLEMENTED)
				.body(Fa3stMessage.from(e));
	}

	@ExceptionHandler({Exception.class})
	public ResponseEntity<Fa3stMessage> exceptionHandler(HttpServletRequest request, Exception e) {
		return ResponseEntity
				.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Fa3stMessage.from(e));
	}

	@ExceptionHandler({AccessDeniedException.class})
	public ResponseEntity<Fa3stMessage> exceptionHandler(HttpServletRequest request,
																	AccessDeniedException e) {
		return ResponseEntity
				.status(HttpStatus.METHOD_NOT_ALLOWED)
				.body(Fa3stMessage.from(e));
	}
}
