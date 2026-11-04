package dev.subscriptionmeter.provider;

import dev.subscriptionmeter.upstream.UpstreamCredentialProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Reads only this deployment's explicitly configured, externally renewed SIWC access-token file. */
public final class OpenAIPlanCredentialFile implements UpstreamCredentialProvider {
    private static final int MAX_BYTES=16384;
    private final Path path;
    public OpenAIPlanCredentialFile(Path path){this.path=path;}
    public String bearerToken()throws IOException{
        if(!Files.isRegularFile(path))throw new IOException("SIWC credential unavailable");
        // Bound the read itself: a separate size check races replacement/growth of the file.
        byte[] bytes;
        try(var input=Files.newInputStream(path)){bytes=input.readNBytes(MAX_BYTES+1);}
        if(bytes.length>MAX_BYTES)throw new IOException("SIWC credential unavailable");
        String token=new String(bytes,StandardCharsets.UTF_8);
        // Permit a text file's final newline, but never repair embedded whitespace/invalid token data.
        if(token.endsWith("\r\n"))token=token.substring(0,token.length()-2);
        else if(token.endsWith("\n"))token=token.substring(0,token.length()-1);
        validate(token);
        return token;
    }
    static void validate(String token)throws IOException{
        if(token==null||token.length()>MAX_BYTES||!token.matches("[A-Za-z0-9._~+/-]+=*")
                ||token.startsWith("sk-")||token.startsWith("sm_"))
            throw new IOException("A dedicated SIWC OAuth access token is required");
    }
}
