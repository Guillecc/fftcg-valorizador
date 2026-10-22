# Reglas de R8 para el APK release.
#
# Por ahora no hace falta ninguna regla propia:
# - El código de la app (cl.guillecc.fftcg) no usa reflexión ni serialización por nombre: el índice
#   y los CSV se leen a mano con org.json (parte de Android, no se minifica).
# - Las clases del manifest (FftcgApp, MainActivity, FileProvider) las conserva AAPT2 solo.
# - Compose, CameraX, ML Kit, Coil y coroutines traen sus propias reglas "consumer" en el AAR.
#
# Si algo falla solo en el APK release (ClassNotFoundException, NoSuchMethodError, el escáner no
# lee), la causa suele ser reflexión en una librería: agregar aquí el -keep mínimo con un comentario.
