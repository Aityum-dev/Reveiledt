# Réveil EDT (Android)

Appli Android qui lit l'emploi du temps iCal de l'université et règle tout seule le réveil : premier cours du jour moins X minutes. Aucun serveur, chaque téléphone lit directement le site de son université.

## Compiler l'APK

1. Ouvrir le dossier `ReveilEDT` dans Android Studio
2. Laisser la synchro Gradle finir
3. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**
4. L'APK est dans `app/build/outputs/apk/release/` (ou `debug/`)

Pour le partager : envoyer le fichier APK aux autres (Discord, mail...). À l'installation, Android demande d'autoriser "sources inconnues".

Tests de la logique de calcul : `./gradlew test` (ou clic droit sur `PlannerTest` > Run).

## Première configuration (chaque personne)

1. Coller le lien iCal (voir plus bas), régler les minutes, **Enregistrer et synchroniser**
2. Corriger chaque bouton orange ⚠ qui s'affiche (notifications, alarmes, plein écran, batterie, volume)
3. **Tester la sonnerie**, verrouiller le téléphone, vérifier que ça sonne et que l'écran s'allume

## Lien iCal ADE

Dans ADE : choisir son groupe, icône **Exporter l'agenda** en bas à gauche, mettre une **date de fin lointaine** (fin d'année scolaire), **Générer URL**. Attention : si la date de fin est dépassée, le calendrier devient vide et la notif passe au rouge "aucun cours trouvé".

## Fonctionnement

| Quand | Quoi |
|---|---|
| Toutes les heures | Retélécharge l'EDT, recalcule et reprogramme le réveil (les changements de cours sont pris en compte jusqu'au matin) |
| Chaque jour à 19h | Synchro garantie, même téléphone en veille |
| Au redémarrage | Reprogramme le réveil depuis le dernier EDT connu, sans attendre le réseau |
| Quand ça sonne | Programme déjà le réveil du jour de cours suivant |

Le réveil utilise `setAlarmClock`, l'API des vraies applis de réveil : il sonne même en mode Doze, en silencieux (canal alarme) et affiche l'icône réveil dans la barre d'état.

## Notification permanente

- 🟢 **Verte** : réveil programmé et EDT vérifié il y a moins de 12h
- 🔴 **Rouge** : pas de lien, alarmes non autorisées, volume des alarmes à 0, aucun cours trouvé, calendrier illisible, ou EDT pas vérifié depuis plus de 12h (le réveil reste quand même programmé avec la dernière version connue)
- ⚪ **Grise** : réveil désactivé volontairement

Si on la balaie, elle revient.

## Limites connues

- Récurrences gérées : quotidiennes et hebdomadaires. Toute autre récurrence met la notif en rouge au lieu d'être ignorée (les exports ADE / Hyperplanning n'en utilisent pas).
- Sur certaines surcouches (Xiaomi, Huawei, Oppo...), il faut en plus autoriser le "démarrage automatique" de l'appli dans les réglages du téléphone.
