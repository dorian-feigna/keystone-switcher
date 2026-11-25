# Keystone Switcher – XGIMI MoGo 2 Pro (Android TV 11)

Enregistre la correction trapézoïdale actuelle sous un nom et bascule d'une position à l'autre en un clic.
Sans root : l'app se connecte à l'ADB local du projecteur (`127.0.0.1:5555`).

## Fonctionnement

1. **+ Nouvelle position** : l'app photographie les réglages système, puis ouvre le réglage trapèze
   (la première fois, tu l'ouvres toi-même à la télécommande ; l'app retient ensuite le menu).
2. Tu ajustes l'image, puis tu appuies sur **Retour** pour revenir dans l'app et tu donnes un nom.
   L'app compare les réglages avant/après et enregistre les valeurs qui ont changé.
3. Un clic sur un nom réécrit ces valeurs (`settings put` / `setprop`).

Limite : si XGIMI stocke la correction dans une zone privée (données de son app ou matériel),
l'app l'indiquera (« Aucun réglage n'a changé ») : ce n'est alors pas faisable sans root.

## Build

Chaque push sur `main` compile l'APK via GitHub Actions et le publie ici :
`https://github.com/<utilisateur>/<depot>/releases/download/latest/app-debug.apk`
(secret `DEBUG_KEYSTORE_BASE64` recommandé pour des mises à jour sans désinstallation).
