package entropydata.snowflake;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BearerTokenSupplier implements Supplier<String> {

  private static final Logger log = LoggerFactory.getLogger(BearerTokenSupplier.class);

  private final String account;
  private final String user;
  private RSAPrivateCrtKey privateKey;

  public BearerTokenSupplier(SnowflakeProperties snowflakeProperties) {
    this.account = snowflakeProperties.account();
    this.user = snowflakeProperties.user();
    privateKey = readPrivateKey(snowflakeProperties.privatekeyfile());
  }

  @Override
  public String get() {
    return generateBearerToken();
  }

  public String generateBearerToken() {
    try {
      RSAPublicKeySpec publicKeySpec =
          new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
      Algorithm algorithm = Algorithm.RSA256(publicKey, privateKey);

      var qualifiedUserName =
          account.toUpperCase(Locale.ROOT)
              + "."
              + user.toUpperCase(Locale.ROOT);

      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      var publicKeyFp =
          "SHA256:" + Base64.getEncoder().encodeToString(digest.digest(publicKey.getEncoded()));

      var issuedTs = new Date();
      var expiresTs = new Date(issuedTs.getTime() + TimeUnit.HOURS.toMillis(1));

      log.debug("Generating JWT for user {}", qualifiedUserName);
      return JWT.create()
          .withIssuer(qualifiedUserName + "." + publicKeyFp)
          .withSubject(qualifiedUserName)
          .withIssuedAt(issuedTs)
          .withExpiresAt(expiresTs)
          .sign(algorithm);
    } catch (Exception e) {
      throw new RuntimeException("Failed to generate JWT", e);
    }
  }

  /**
   * Creates a RSA private key from a P8 file
   *
   * @param file a private key P8 file
   * @return RSAPrivateCrtKey instance
   */
  private static RSAPrivateCrtKey readPrivateKey(File file) {
    try {
      String key = Files.readString(file.toPath(), Charset.defaultCharset());

      String privateKeyPEM =
          key.replace("-----BEGIN PRIVATE KEY-----", "") // pragma: allowlist secret
              .replaceAll(System.lineSeparator(), "")
              .replace("-----END PRIVATE KEY-----", "");

      byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);

      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
      return (RSAPrivateCrtKey) keyFactory.generatePrivate(keySpec);
    } catch (Exception e) {
      throw new RuntimeException("Failed to generate JWT", e);
    }
  }

}
