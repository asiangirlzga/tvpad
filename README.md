# TV Pad (Wi-Fi controller)

Phone app that works as a game controller over your phone's hotspot. No Bluetooth, no router.

The phone runs a tiny server (port 8765). Games on the TV connect to it and receive key presses
(D-pad, A/B/X/Y, L1/R1 = keys 1/3, L2/R2, Start, Back, OK), so each game needs the small `PadClient` receiver.
