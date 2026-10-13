package com.locky.app;

import com.nimbusds.jwt.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import java.io.IOException;

final class IdentityCheck {
    static JWTClaimsSet validate(String raw,JWKSet keys,String issuer,String client,String nonce,long now) throws Exception {
        SignedJWT jwt=SignedJWT.parse(raw);
        JWK k=keys.getKeyByKeyId(jwt.getHeader().getKeyID());
        boolean valid=false;
        if(k instanceof RSAKey&&JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()))valid=jwt.verify(new RSASSAVerifier(((RSAKey)k).toRSAPublicKey()));
        if(k instanceof ECKey&&JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm()))valid=jwt.verify(new ECDSAVerifier(((ECKey)k).toECPublicKey()));
        if(!valid)throw new IOException("ID token 서명을 검증하지 못했어.");
        JWTClaimsSet c=jwt.getJWTClaimsSet();
        if(!issuer.equals(c.getIssuer()))throw new IOException("ID token 발급자가 일치하지 않아.");
        if(!c.getAudience().contains(client))throw new IOException("ID token의 앱 등록 정보가 일치하지 않아.");
        if(c.getExpirationTime()==null||c.getExpirationTime().getTime()<now)throw new IOException("ID token이 만료됐어. 기기의 날짜와 시간을 확인하고 다시 연결해줘.");
        if(!nonce.equals(c.getStringClaim("nonce")))throw new IOException("ID token의 로그인 검증 값이 일치하지 않아.");
        if(c.getSubject()==null||c.getSubject().isBlank())throw new IOException("ID token에 계정 식별 정보가 없어.");
        if(c.getNotBeforeTime()!=null&&c.getNotBeforeTime().getTime()>now+30000)throw new IOException("ID token 사용 시간이 아직 되지 않았어. 기기의 날짜와 시간을 확인해줘.");
        return c;
    }
}
