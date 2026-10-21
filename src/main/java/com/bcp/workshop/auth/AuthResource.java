package com.bcp.workshop.auth;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
    @Inject
    AuthenticationService authentication;

    @POST
    @Path("/login")
    public Response login(LoginRequest request) {
        try {
            if (request == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Request is null")).build();
            }
            if (request.username == null || request.username.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Username is required")).build();
            }
            if (request.password == null || request.password.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Password is required")).build();
            }
            if (request.client == null || request.client.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Client is required")).build();
            }
            var authenticated = authentication.authenticate(request.username, request.password, request.client);
            if (authenticated == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("message", "Invalid user " + request.username + " or password")).build();
            }
            return Response.ok(LoginResponse.from(authenticated)).build();
        } catch (Exception error) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of(
                    "message", error.getMessage() == null ? "Unknown error" : error.getMessage(),
                    "exception", error.getClass().getName()
            )).build();
        }
    }
}
