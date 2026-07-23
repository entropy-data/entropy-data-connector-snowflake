package entropydata.snowflake;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BearerTokenSupplierTest {

  @TempDir
  Path tempDir;

  private KeyPair keyPair;
  private BearerTokenSupplier supplier;

  @BeforeEach
  void setUp() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();

    // BearerTokenSupplier reads a PKCS#8 PEM: header/footer plus base64 on its own line.
    var keyFile = tempDir.resolve("test_key.p8");
    var pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    Files.writeString(keyFile, pem);

    var properties = new SnowflakeProperties("myaccount", "myuser", keyFile.toFile(), null, null);
    supplier = new BearerTokenSupplier(properties);
  }

  @Test
  void signsAVerifiableRs256Token() {
    var token = supplier.get();

    // verify() throws if the signature does not match the public key
    DecodedJWT decoded = JWT.require(Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), null))
        .build()
        .verify(token);

    assertThat(decoded.getAlgorithm()).isEqualTo("RS256");
  }

  @Test
  void setsSnowflakeClaims() {
    var decoded = JWT.decode(supplier.get());

    // account and user are upper-cased and qualified; the issuer additionally carries the key fingerprint
    assertThat(decoded.getSubject()).isEqualTo("MYACCOUNT.MYUSER");
    assertThat(decoded.getIssuer()).startsWith("MYACCOUNT.MYUSER.SHA256:");
    assertThat(decoded.getExpiresAt().getTime() - decoded.getIssuedAt().getTime())
        .isEqualTo(3_600_000L);
  }
}
