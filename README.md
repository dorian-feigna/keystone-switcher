# Keystone Switcher – XGIMI MoGo 2 Pro (Android TV 11)

Enregistre la correction trapézoïdale actuelle sous un nom et bascule d'une position à l'autre en un clic.
Sans root : l'app se connecte à l'ADB local du projecteur (`127.0.0.1:5555`).

## Fonctionnement

- **Ouvrir le réglage trapèze** : ouvre directement le menu de correction manuelle
  (la toute première fois, ouvre-le à la télécommande puis reviens dans l'app : il est retenu).
- **Enregistrer la position actuelle** : lit les 4 coins (`vendor.mstar.pos_{lt,rt,rb,lb}_offset`, format `x:y`)
  et les enregistre sous un nom.
- **Clic sur un nom** : Android interdit d'écrire ces valeurs sans root, donc l'app pilote le menu XGIMI
  en boucle fermée : coin haut-gauche sélectionné à l'ouverture, flèches pour déplacer, relecture de la position
  après chaque envoi, OK pour passer au coin suivant, Retour pour valider.
  L'ordre des coins et le sens des flèches sont appris automatiquement au premier usage.

## Build

Chaque push sur `main` compile l'APK via GitHub Actions et le publie ici :
`https://github.com/<utilisateur>/<depot>/releases/download/latest/app-debug.apk`
(secret `DEBUG_KEYSTORE_BASE64` recommandé pour des mises à jour sans désinstallation).
