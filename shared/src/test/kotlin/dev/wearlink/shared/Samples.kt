package dev.wearlink.shared

object Samples {
    const val VLESS_REALITY =
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@nl1.example.com:443?type=tcp&security=reality" +
            "&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&fp=chrome&sni=www.microsoft.com" +
            "&sid=6ba85179e30d4fc2&spx=%2F&flow=xtls-rprx-vision#🇳🇱 Нидерланды"

    const val VLESS_WS =
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?encryption=none&security=tls" +
            "&sni=cdn.example.com&alpn=h2%2Chttp%2F1.1&fp=firefox&type=ws&host=cdn.example.com" +
            "&path=%2Fws%3Fed%3D2048#WS%20CDN"

    const val VLESS_GRPC =
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@g.example.com:443?security=tls&type=grpc" +
            "&serviceName=grpc-svc&sni=g.example.com#gRPC"

    const val HY2 =
        "hysteria2://p%40ss%2Fword@hy.example.com:443/?sni=hy.example.com&insecure=1&obfs=salamander" +
            "&obfs-password=salamander-secret&mport=20000-30000#Hy2"

    const val TROJAN = "trojan://tr-pass@tr.example.com:443?security=tls&sni=tr.example.com&type=grpc&serviceName=tr-grpc#Trojan%20gRPC"

    const val VMESS = "vmess://eyJ2IjogIjIiLCAicHMiOiAiVk1lc3MgV1MiLCAiYWRkIjogInZtLmV4YW1wbGUuY29tIiwgInBvcnQiOiAiNDQzIiwgImlkIjogImI4MzEzODFkLTYzMjQtNGQ1My1hZDRmLThjZGE0OGIzMDgxMSIsICJhaWQiOiAiMCIsICJzY3kiOiAiYXV0byIsICJuZXQiOiAid3MiLCAidHlwZSI6ICJub25lIiwgImhvc3QiOiAidm0uZXhhbXBsZS5jb20iLCAicGF0aCI6ICIvdm0iLCAidGxzIjogInRscyIsICJzbmkiOiAidm0uZXhhbXBsZS5jb20iLCAiYWxwbiI6ICIiLCAiZnAiOiAiY2hyb21lIn0="

    const val SS_SIP002 = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpzcy1wYXNz@ss.example.com:8388#SS%20Chacha"

    const val SS_2022 = "ss://2022-blake3-aes-128-gcm:AAECAwQFBgcICQoLDA0ODw%3D%3D@ss2.example.com:443?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#SS2022"

    const val SS_LEGACY = "ss://YWVzLTI1Ni1nY206bGVnYWN5LXBhc3NANS42LjcuODo4Mzg4#Legacy"

    const val TUIC = "tuic://2DD61D93-75D8-4DA4-AC0E-6AECE7EAC365:tuic-pass@tuic.example.com:443?congestion_control=bbr&udp_relay_mode=quic&alpn=h3&sni=tuic.example.com&allow_insecure=1#TUIC"

    const val ANYTLS = "anytls://any-pass@any.example.com:443?sni=any.example.com&insecure=0&fp=chrome#AnyTLS"

    const val HYSTERIA1 = "hysteria://hy1.example.com:443?protocol=udp&auth=hy1-auth&peer=hy1.example.com&insecure=1&upmbps=20&downmbps=100&alpn=hysteria&obfsParam=hy1-obfs#Hy1"
}
