import React, { Component } from 'react';
import styles from './StacklineStatsComponent.css';

export default class StacklineStatsComponent extends Component {
  constructor (props) {
    super(props);
    this.state = {
    };
  }

  render () {
    return (
       <div className={`${this.props.highlight ? styles.highlight : this.props.subdued && styles.subdued} ${styles.statContainer}`} style={this.props.style}>
          { this.props.samples ? (
            <div className={`${styles.pill} mdl-color-text--primary`}>
              <div className={styles.number}>{this.props.samples}</div>
              <div className={styles.percentage}>
                <div className={styles.shade} style={{ width: `${this.props.samplesPct}%` }}></div>
                {this.props.samplesPct}%
              </div>
            </div>
          ) : <div>&nbsp;</div> }
      </div>
    );
  }
}