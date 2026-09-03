# Splashhys SG Plugin 2.0.1

Paper 1.21.11 / Java 21.

## Build

Run:

    mvn package

The jar is:

    target/Splashhys-SG-Plugin.jar

A GitHub Actions workflow is included. Push this repository to GitHub and open **Actions** to build the jar automatically.

## Commands

Players:
- `/sg queue`
- `/sg lobby`
- `/sg status`
- `/sg players list`

Frontman:
- `/sg start` — lock/start the event after the configured queue minimum is reached
- `/sg start rlgl`
- `/sg start dalgona`
- `/sg start nightfight`
- `/sg start tugofwar`
- `/sg start marbles`
- `/sg start glassbridge`
- `/sg start squidgame`
- `/sg stop`
- `/sg eliminate <player>`
- `/sg spectator <player>`
- `/sg setup <game>`
- `/sg setup status`
- `/sg reset <game>`
- `/sg event reset`
- `/sg staff add <player> frontman`
- `/sg staff add <player> guard`
- `/sg staff remove <player>`
- `/sg book`
- `/sg reload`

## Setup

Setup is click-based. Use `/sg setup <game>` as a Frontman, then use the compass it gives you.

The setup wizard records points and two-corner regions. Setup is saved in `plugins/SplashhysSG/setup.yml`.

Dalgona uses an in-world reference region: the four teams must copy the reference blocks into their build regions. The first three teams to finish are safe; the remaining team is eliminated.

## Important

The plugin does not control doors. Use your existing command blocks / setblock commands for doors.

This is a complete source project with the requested event framework and game mechanics. Because the build environment here does not have the Paper API cached, the jar must be compiled by Maven or the included GitHub Actions workflow.
