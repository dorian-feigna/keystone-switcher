# Keystone Switcher – XGIMI MoGo 2 Pro (Android TV 11)

Application Android TV sans root qui rejoue des profils de correction trapézoïdale
via ADB local (`dadb` → `127.0.0.1:5555` → `input keyevent`), protégée par un
service d'accessibilité coupe-circuit.

## 1. Préparer le projecteur (une fois)

1. Paramètres → Préférences de l'appareil → À propos → cliquer 7 fois sur « Build ».
2. Options pour les développeurs → activer **Débogage USB/ADB** et, s'il existe, **Débogage réseau / ADB over network** (port 5555).
3. Installer l'application *Downloader* (AFTVnews) depuis le Play Store du projecteur.

## 2. Compiler via GitHub Actions

1. Créer un dépôt **public** (Downloader ne peut pas s'authentifier sur un dépôt privé) et y pousser ces fichiers sur `main`.
2. *(Recommandé, une seule fois)* Actions → « Générer le keystore de signature » → Run workflow.
   Télécharger l'artefact, copier le contenu du `.txt` dans Settings → Secrets and variables → Actions →
   nouveau secret `DEBUG_KEYSTORE_BASE64`, puis supprimer l'artefact.
   Sans ce secret, chaque build a une signature différente : il faut désinstaller l'app avant chaque mise à jour
   (et réautoriser la clé ADB).
3. Chaque push sur `main` publie l'APK ici :
   `https://github.com/<utilisateur>/<depot>/releases/download/latest/app-debug.apk`

## 3. Première utilisation

1. Lancer l'app → « Tester / Autoriser ADB » → accepter la fenêtre RSA à l'écran en cochant **Toujours autoriser**.
2. Activer le coupe-circuit : « Activer via ADB » (ou Paramètres d'accessibilité).
3. « Calibrer / Détecter le menu XGIMI » → « Lancer la détection » → ouvrir le menu trapèze manuel pendant 8 s.
4. Revenir dans l'app, vérifier le rapport, compléter les séquences (remise à zéro, sélection/validation des coins),
   puis utiliser « Tester l'ouverture du menu » et « Tester ouverture + remise à zéro ».
5. Créer les profils (Mur salon, Plafond, Bureau…) et les lancer en un clic.

**Arrêt d'urgence** : touche Retour de la télécommande, ou quitter le menu XGIMI.

## Convention des décalages

| Axe | Valeur positive | Valeur négative |
|-----|-----------------|-----------------|
| X   | DROITE (22)     | GAUCHE (21)     |
| Y   | HAUT (19)       | BAS (20)        |

Format JSON d'un profil (stocké dans DataStore) :

```json
{
  "profileName": "Projection Plafond",
  "stepDelayMs": 300,
  "corners": {
    "topLeft": { "x": 12, "y": -8 },
    "topRight": { "x": -12, "y": -8 },
    "bottomLeft": { "x": 4, "y": 0 },
    "bottomRight": { "x": -4, "y": 0 }
  }
}
```
