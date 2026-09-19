package com.snigji.unshortener.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.InetAddress;
import java.net.URI;

@ApplicationScoped
public class AllowAllGuard implements Guard {

    @Override
    public void checkUrl(URI url) {
    }

    @Override
    public void checkIp(String host, InetAddress ip) {
    }
}
