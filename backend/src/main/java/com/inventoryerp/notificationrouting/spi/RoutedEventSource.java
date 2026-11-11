package com.inventoryerp.notificationrouting.spi;

import java.util.List;

/**
 * One module's routable events. Declare the bean conditional on the module's flag, so a disabled module's events are
 * neither offered in the settings nor subscribed to.
 */
public interface RoutedEventSource {

    List<RoutedEvent> routedEvents();
}
