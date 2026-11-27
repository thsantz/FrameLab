# FrameLab

Editor de vídeo Android em desenvolvimento, com foco inicial em tablets.

## Base 0.1.0
- Kotlin + Jetpack Compose.
- AndroidX Media3 ExoPlayer para importar e reproduzir vídeos.
- Seleção de múltiplos vídeos pelo seletor de documentos do Android.
- Pipeline GitHub Actions para compilar APK de depuração automaticamente.

## Estado honesto
Esta versão é apenas a fundação do aplicativo: ainda não possui edição real da timeline, cortes, transições, áudio, títulos, exportação, legendas automáticas, upscale por IA ou HDR10. Os controles dessas ferramentas ainda são visuais. O APK de debug não é uma versão pronta para uso profissional.

## Compilação
Cada push na branch `main` executa o workflow **Android debug APK**. O APK fica disponível em Actions → execução mais recente → Artifacts.
