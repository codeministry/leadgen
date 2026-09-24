import {isDevMode} from '@angular/core';
import {withDevToolsStub, withDevtools} from '@angular-architects/ngrx-toolkit';

/**
 * Connects a store to the Redux DevTools browser extension in a dev build and to a
 * no-op stub in a production one, so a deployed instance never exposes its state
 * to whoever has the extension installed.
 */
export const withAppDevtools: typeof withDevtools = isDevMode() ? withDevtools : withDevToolsStub;
