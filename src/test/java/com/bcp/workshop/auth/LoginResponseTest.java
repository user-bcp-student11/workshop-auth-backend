package com.bcp.workshop.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginResponseTest {
    @Test
    void preservesTheCompletePublicResponse() {
        var response = responseFor("Workshop Student", "STUDENT");
        assertEquals("qa-token-fixture", response.token());
        assertEquals(3600, response.expiresIn());
        assertEquals(new LoginResponse.User("student", "Workshop Student", "STUDENT"), response.user());
        assertEquals("authenticated-with-legacy-flow", response.debug());
    }

    @Test
    void escapesHtml4IncludingQuotesAndAmpersands() {
        assertEquals("&lt;b&gt;QA &amp; &quot;Test&quot;&lt;/b&gt;",
                responseFor("<b>QA & \"Test\"</b>", "STUDENT").user().displayName());
        assertEquals("'apostrophe' &amp; &lt;tag&gt;",
                responseFor("'apostrophe' & <tag>", "STUDENT").user().displayName());
    }

    @Test
    void preservesUnicodeAndEmptyDisplayNames() {
        assertEquals("caf&eacute; \u6f22", responseFor("caf\u00e9 \u6f22", "STUDENT").user().displayName());
        assertEquals("", responseFor("", "STUDENT").user().displayName());
    }

    @Test
    void preservesExistingRolesWithoutNormalizingThem() {
        assertEquals("CUSTOM_ROLE", responseFor("QA", "CUSTOM_ROLE").user().role());
    }

    private LoginResponse responseFor(String displayName, String role) {
        var user = new UserRecord(41, "student", displayName, role);
        return LoginResponse.from(new AuthenticationService.AuthenticatedUser(user, "qa-token-fixture"));
    }
}