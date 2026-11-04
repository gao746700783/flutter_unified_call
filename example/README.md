# flutter_unified_call example

This app demonstrates the plugin's cross-platform call lifecycle, durable event
recovery, permissions, foreground banner and Android audio endpoint controls. Its
plugin dependency uses the parent directory, so it always exercises this checkout.

## Run

From this directory, install dependencies and launch on a configured device or
browser:

```sh
flutter pub get
flutter run
```

The example requests notification permissions through explicit user actions.
Configure platform push delivery and native notification delegate integration
before testing background actions; see the [platform setup guides](../doc/README.md).

## More information

- [Package overview and API](../README.md)
- [Implementation status and verification limits](../doc/IMPLEMENTATION_STATUS.md)
