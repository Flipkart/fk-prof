import React from 'react';
import styles from './LoaderComponent.css';

export default function Loader ({ style }) {
  return (
    <div
      className={styles.loader}
      style={style}
    >
      Loading...
    </div>
  );
}
